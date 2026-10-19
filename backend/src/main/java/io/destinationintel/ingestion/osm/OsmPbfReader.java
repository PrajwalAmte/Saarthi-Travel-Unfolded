package io.destinationintel.ingestion.osm;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Streaming reader for the OpenStreetMap PBF format, implemented from the published format
 * specification (https://wiki.openstreetmap.org/wiki/PBF_Format) on protobuf-java's wire
 * decoder. Blocks are decoded one at a time, so memory use does not grow with file size.
 * Supports raw and zlib-compressed blobs, dense and plain nodes, ways and relations (decoded
 * only when the handler asks for them).
 */
public final class OsmPbfReader {

    public record Header(Long replicationTimestamp, Long replicationSequence, String replicationBaseUrl,
                         String writingProgram, List<String> requiredFeatures) {}

    public interface Handler {
        default void header(Header header) {}

        /** Decide early, per string, whether an object with this tag key may be relevant. */
        boolean interestingKey(String key);

        default boolean wantNodes() {
            return true;
        }

        default boolean wantWays() {
            return true;
        }

        /** Called for every node when {@link #wantAllNodeCoordinates()} is true, else only for tagged ones. */
        void node(long id, double lat, double lon, Map<String, String> tags, int version, long timestampSeconds);

        default boolean wantAllNodeCoordinates() {
            return false;
        }

        void way(long id, long[] refs, Map<String, String> tags, int version, long timestampSeconds);

        /**
         * Ways are normally delivered only when they carry an interesting tag. Returning true here
         * delivers an untagged (or uninteresting) way too, with empty tags, e.g. the outer ring of a
         * multipolygon relation.
         */
        default boolean wantUntaggedWay(long id) {
            return false;
        }

        default boolean wantRelations() {
            return false;
        }

        /** Member types follow the format: 0 node, 1 way, 2 relation. Only relations with interesting tags are delivered. */
        default void relation(long id, long[] memberIds, int[] memberTypes, String[] memberRoles, Map<String, String> tags,
                              int version, long timestampSeconds) {}

        default void relationSeen() {}
    }

    private static final Map<String, String> NO_TAGS = Map.of();
    private static final int MAX_BLOB = 64 * 1024 * 1024;

    private OsmPbfReader() {}

    public static void read(InputStream input, Handler handler) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(input, 1 << 20));
        Inflater inflater = new Inflater();
        try {
            while (true) {
                int headerLength;
                try {
                    headerLength = in.readInt();
                } catch (EOFException eof) {
                    return;
                }
                if (headerLength <= 0 || headerLength > 64 * 1024) {
                    throw new IOException("Corrupt PBF: invalid blob header length " + headerLength);
                }
                byte[] headerBytes = in.readNBytes(headerLength);
                String type = null;
                int dataSize = -1;
                CodedInputStream h = CodedInputStream.newInstance(headerBytes);
                for (int tag = h.readTag(); tag != 0; tag = h.readTag()) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> type = h.readString();
                        case 3 -> dataSize = h.readInt32();
                        default -> h.skipField(tag);
                    }
                }
                if (dataSize < 0 || dataSize > MAX_BLOB) {
                    throw new IOException("Corrupt PBF: invalid blob size " + dataSize);
                }
                byte[] blob = in.readNBytes(dataSize);
                if (blob.length != dataSize) {
                    throw new EOFException("Truncated PBF: expected " + dataSize + " bytes, got " + blob.length);
                }
                byte[] data = decompress(blob, inflater);
                if ("OSMHeader".equals(type)) {
                    handler.header(parseHeader(data));
                } else if ("OSMData".equals(type)) {
                    parsePrimitiveBlock(data, handler);
                }
            }
        } finally {
            inflater.end();
        }
    }

    static byte[] decompress(byte[] blob, Inflater inflater) throws IOException {
        CodedInputStream b = CodedInputStream.newInstance(blob);
        byte[] raw = null;
        byte[] zlib = null;
        int rawSize = -1;
        for (int tag = b.readTag(); tag != 0; tag = b.readTag()) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1 -> raw = b.readByteArray();
                case 2 -> rawSize = b.readInt32();
                case 3 -> zlib = b.readByteArray();
                case 4, 5, 6, 7 -> throw new IOException("Unsupported PBF compression (field " + WireFormat.getTagFieldNumber(tag) + ")");
                default -> b.skipField(tag);
            }
        }
        if (raw != null) {
            return raw;
        }
        if (zlib == null || rawSize < 0 || rawSize > MAX_BLOB) {
            throw new IOException("Corrupt PBF blob");
        }
        byte[] out = new byte[rawSize];
        inflater.reset();
        inflater.setInput(zlib);
        try {
            int n = 0;
            while (n < rawSize) {
                int got = inflater.inflate(out, n, rawSize - n);
                if (got == 0 && (inflater.finished() || inflater.needsInput())) {
                    break;
                }
                n += got;
            }
            if (n != rawSize) {
                throw new IOException("Corrupt PBF: inflated " + n + " of " + rawSize + " bytes");
            }
        } catch (DataFormatException e) {
            throw new IOException("Corrupt PBF zlib data", e);
        }
        return out;
    }

    static Header parseHeader(byte[] data) throws IOException {
        CodedInputStream c = CodedInputStream.newInstance(data);
        Long ts = null;
        Long seq = null;
        String url = null;
        String program = null;
        List<String> required = new ArrayList<>();
        for (int tag = c.readTag(); tag != 0; tag = c.readTag()) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 4 -> required.add(c.readString());
                case 16 -> program = c.readString();
                case 32 -> ts = c.readInt64();
                case 33 -> seq = c.readInt64();
                case 34 -> url = c.readString();
                default -> c.skipField(tag);
            }
        }
        for (String f : required) {
            if (!f.equals("OsmSchema-V0.6") && !f.equals("DenseNodes") && !f.equals("HistoricalInformation")) {
                throw new IOException("PBF requires unsupported feature: " + f);
            }
        }
        return new Header(ts, seq, url, program, required);
    }

    private static void parsePrimitiveBlock(byte[] data, Handler handler) throws IOException {
        CodedInputStream c = CodedInputStream.newInstance(data);
        List<byte[]> strings = new ArrayList<>();
        List<byte[]> groups = new ArrayList<>();
        int granularity = 100;
        int dateGranularity = 1000;
        long latOffset = 0;
        long lonOffset = 0;
        for (int tag = c.readTag(); tag != 0; tag = c.readTag()) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1 -> {
                    int limit = c.pushLimit(c.readRawVarint32());
                    for (int t = c.readTag(); t != 0; t = c.readTag()) {
                        if (WireFormat.getTagFieldNumber(t) == 1) {
                            strings.add(c.readByteArray());
                        } else {
                            c.skipField(t);
                        }
                    }
                    c.popLimit(limit);
                }
                case 2 -> groups.add(c.readByteArray());
                case 17 -> granularity = c.readInt32();
                case 18 -> dateGranularity = c.readInt32();
                case 19 -> latOffset = c.readInt64();
                case 20 -> lonOffset = c.readInt64();
                default -> c.skipField(tag);
            }
        }
        Block block = new Block(strings, granularity, dateGranularity, latOffset, lonOffset, handler);
        for (byte[] g : groups) {
            block.group(g);
        }
    }

    /** One decoded primitive block: string table, coordinate scaling and the handler. */
    private static final class Block {
        private final String[] strings;
        private final boolean[] interesting;
        private final int granularity;
        private final int dateGranularity;
        private final long latOffset;
        private final long lonOffset;
        private final Handler handler;

        Block(List<byte[]> raw, int granularity, int dateGranularity, long latOffset, long lonOffset, Handler handler) {
            this.strings = new String[raw.size()];
            this.interesting = new boolean[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                strings[i] = new String(raw.get(i), StandardCharsets.UTF_8);
                interesting[i] = handler.interestingKey(strings[i]);
            }
            this.granularity = granularity;
            this.dateGranularity = dateGranularity;
            this.latOffset = latOffset;
            this.lonOffset = lonOffset;
            this.handler = handler;
        }

        double lat(long v) {
            return 1e-9 * (latOffset + (long) granularity * v);
        }

        double lon(long v) {
            return 1e-9 * (lonOffset + (long) granularity * v);
        }

        void group(byte[] data) throws IOException {
            CodedInputStream c = CodedInputStream.newInstance(data);
            for (int tag = c.readTag(); tag != 0; tag = c.readTag()) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> {
                        byte[] node = c.readByteArray();
                        if (handler.wantNodes()) {
                            node(node);
                        }
                    }
                    case 2 -> {
                        byte[] dense = c.readByteArray();
                        if (handler.wantNodes()) {
                            dense(dense);
                        }
                    }
                    case 3 -> {
                        byte[] way = c.readByteArray();
                        if (handler.wantWays()) {
                            way(way);
                        }
                    }
                    case 4 -> {
                        if (handler.wantRelations()) {
                            relation(c.readByteArray());
                        } else {
                            c.skipField(tag);
                        }
                        handler.relationSeen();
                    }
                    default -> c.skipField(tag);
                }
            }
        }

        private void dense(byte[] data) throws IOException {
            CodedInputStream c = CodedInputStream.newInstance(data);
            long[] ids = null;
            long[] lats = null;
            long[] lons = null;
            int[] keysVals = null;
            int[] versions = null;
            long[] timestamps = null;
            for (int tag = c.readTag(); tag != 0; tag = c.readTag()) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> ids = packedSInt64(c);
                    case 5 -> {
                        int limit = c.pushLimit(c.readRawVarint32());
                        for (int t = c.readTag(); t != 0; t = c.readTag()) {
                            switch (WireFormat.getTagFieldNumber(t)) {
                                case 1 -> versions = packedInt32(c);
                                case 2 -> timestamps = packedSInt64(c);
                                default -> c.skipField(t);
                            }
                        }
                        c.popLimit(limit);
                    }
                    case 8 -> lats = packedSInt64(c);
                    case 9 -> lons = packedSInt64(c);
                    case 10 -> keysVals = packedInt32(c);
                    default -> c.skipField(tag);
                }
            }
            if (ids == null || lats == null || lons == null) {
                return;
            }
            long id = 0;
            long lat = 0;
            long lon = 0;
            long ts = 0;
            int kv = 0;
            boolean all = handler.wantAllNodeCoordinates();
            for (int i = 0; i < ids.length; i++) {
                id += ids[i];
                lat += lats[i];
                lon += lons[i];
                if (timestamps != null && i < timestamps.length) {
                    ts += timestamps[i];
                }
                Map<String, String> tags = NO_TAGS;
                boolean relevant = false;
                if (keysVals != null) {
                    int start = kv;
                    while (kv < keysVals.length && keysVals[kv] != 0) {
                        if (interesting[keysVals[kv]]) {
                            relevant = true;
                        }
                        kv += 2;
                    }
                    int end = kv;
                    kv++;
                    if (relevant) {
                        tags = new HashMap<>();
                        for (int k = start; k < end; k += 2) {
                            tags.put(strings[keysVals[k]], strings[keysVals[k + 1]]);
                        }
                    }
                }
                if (relevant || all) {
                    int version = versions != null && i < versions.length ? versions[i] : 0;
                    handler.node(id, lat(lat), lon(lon), tags, version, ts * dateGranularity / 1000);
                }
            }
        }

        private void node(byte[] data) throws IOException {
            CodedInputStream c = CodedInputStream.newInstance(data);
            long id = 0;
            long lat = 0;
            long lon = 0;
            int[] keys = null;
            int[] vals = null;
            int version = 0;
            long ts = 0;
            for (int tag = c.readTag(); tag != 0; tag = c.readTag()) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> id = c.readSInt64();
                    case 2 -> keys = packedUInt32(c, tag);
                    case 3 -> vals = packedUInt32(c, tag);
                    case 4 -> {
                        long[] info = info(c);
                        version = (int) info[0];
                        ts = info[1];
                    }
                    case 8 -> lat = c.readSInt64();
                    case 9 -> lon = c.readSInt64();
                    default -> c.skipField(tag);
                }
            }
            Map<String, String> tags = tags(keys, vals);
            if (tags != NO_TAGS || handler.wantAllNodeCoordinates()) {
                handler.node(id, lat(lat), lon(lon), tags, version, ts * dateGranularity / 1000);
            }
        }

        private void way(byte[] data) throws IOException {
            CodedInputStream c = CodedInputStream.newInstance(data);
            long id = 0;
            int[] keys = null;
            int[] vals = null;
            long[] refs = null;
            int version = 0;
            long ts = 0;
            for (int tag = c.readTag(); tag != 0; tag = c.readTag()) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> id = c.readInt64();
                    case 2 -> keys = packedUInt32(c, tag);
                    case 3 -> vals = packedUInt32(c, tag);
                    case 4 -> {
                        long[] info = info(c);
                        version = (int) info[0];
                        ts = info[1];
                    }
                    case 8 -> refs = packedSInt64(c);
                    default -> c.skipField(tag);
                }
            }
            Map<String, String> tags = tags(keys, vals);
            if (tags == NO_TAGS && !handler.wantUntaggedWay(id)) {
                return;
            }
            long[] absolute = refs == null ? new long[0] : new long[refs.length];
            long ref = 0;
            for (int i = 0; i < absolute.length; i++) {
                ref += refs[i];
                absolute[i] = ref;
            }
            handler.way(id, absolute, tags, version, ts * dateGranularity / 1000);
        }

        private void relation(byte[] data) throws IOException {
            CodedInputStream c = CodedInputStream.newInstance(data);
            long id = 0;
            int[] keys = null;
            int[] vals = null;
            int[] roles = null;
            long[] memids = null;
            int[] types = null;
            int version = 0;
            long ts = 0;
            for (int tag = c.readTag(); tag != 0; tag = c.readTag()) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> id = c.readInt64();
                    case 2 -> keys = packedUInt32(c, tag);
                    case 3 -> vals = packedUInt32(c, tag);
                    case 4 -> {
                        long[] info = info(c);
                        version = (int) info[0];
                        ts = info[1];
                    }
                    case 8 -> roles = packedInt32(c);
                    case 9 -> memids = packedSInt64(c);
                    case 10 -> types = packedInt32(c);
                    default -> c.skipField(tag);
                }
            }
            Map<String, String> tags = tags(keys, vals);
            if (tags == NO_TAGS) {
                return;
            }
            int n = memids == null ? 0 : memids.length;
            long[] ids = new long[n];
            int[] memberTypes = new int[n];
            String[] memberRoles = new String[n];
            long ref = 0;
            for (int i = 0; i < n; i++) {
                ref += memids[i];
                ids[i] = ref;
                memberTypes[i] = types != null && i < types.length ? types[i] : 0;
                memberRoles[i] = roles != null && i < roles.length && roles[i] >= 0 && roles[i] < strings.length ? strings[roles[i]] : "";
            }
            handler.relation(id, ids, memberTypes, memberRoles, tags, version, ts * dateGranularity / 1000);
        }

        private Map<String, String> tags(int[] keys, int[] vals) {
            if (keys == null || vals == null || keys.length == 0) {
                return NO_TAGS;
            }
            boolean relevant = false;
            for (int k : keys) {
                if (interesting[k]) {
                    relevant = true;
                    break;
                }
            }
            if (!relevant) {
                return NO_TAGS;
            }
            Map<String, String> tags = new HashMap<>();
            for (int i = 0; i < keys.length && i < vals.length; i++) {
                tags.put(strings[keys[i]], strings[vals[i]]);
            }
            return tags;
        }

        private static long[] info(CodedInputStream c) throws IOException {
            long version = 0;
            long ts = 0;
            int limit = c.pushLimit(c.readRawVarint32());
            for (int t = c.readTag(); t != 0; t = c.readTag()) {
                switch (WireFormat.getTagFieldNumber(t)) {
                    case 1 -> version = c.readInt32();
                    case 2 -> ts = c.readInt64();
                    default -> c.skipField(t);
                }
            }
            c.popLimit(limit);
            return new long[] {version, ts};
        }
    }

    static long[] packedSInt64(CodedInputStream c) throws IOException {
        int len = c.readRawVarint32();
        int limit = c.pushLimit(len);
        long[] out = new long[Math.max(4, len)];
        int n = 0;
        while (c.getBytesUntilLimit() > 0) {
            if (n == out.length) {
                out = java.util.Arrays.copyOf(out, n * 2);
            }
            out[n++] = c.readSInt64();
        }
        c.popLimit(limit);
        return java.util.Arrays.copyOf(out, n);
    }

    static int[] packedInt32(CodedInputStream c) throws IOException {
        int len = c.readRawVarint32();
        int limit = c.pushLimit(len);
        int[] out = new int[Math.max(4, len)];
        int n = 0;
        while (c.getBytesUntilLimit() > 0) {
            if (n == out.length) {
                out = java.util.Arrays.copyOf(out, n * 2);
            }
            out[n++] = c.readInt32();
        }
        c.popLimit(limit);
        return java.util.Arrays.copyOf(out, n);
    }

    static int[] packedUInt32(CodedInputStream c, int tag) throws IOException {
        if (WireFormat.getTagWireType(tag) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            return new int[] {c.readUInt32()};
        }
        int len = c.readRawVarint32();
        int limit = c.pushLimit(len);
        int[] out = new int[Math.max(4, len)];
        int n = 0;
        while (c.getBytesUntilLimit() > 0) {
            if (n == out.length) {
                out = java.util.Arrays.copyOf(out, n * 2);
            }
            out[n++] = c.readUInt32();
        }
        c.popLimit(limit);
        return java.util.Arrays.copyOf(out, n);
    }
}

package io.destinationintel.ingestion.osm;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * Test-only writer for tiny synthetic OSM PBF files, encoded field by field from the format
 * specification so the reader can be tested without a real extract.
 */
final class PbfFixture {

    record Node(long id, double lat, double lon, String[] tags) {}

    record Way(long id, long[] refs, String[] tags) {}

    /** Members as {type (0 node, 1 way, 2 relation), id}; roles parallel to members. */
    record Relation(long id, long[][] members, String[] roles, String[] tags) {}

    private PbfFixture() {}

    static byte[] file(List<Node> nodes, List<Way> ways) throws IOException {
        return file(nodes, ways, List.of());
    }

    static byte[] file(List<Node> nodes, List<Way> ways, List<Relation> relations) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        writeBlob(data, "OSMHeader", header());
        writeBlob(data, "OSMData", primitiveBlock(nodes, ways, relations));
        return out.toByteArray();
    }

    private static byte[] header() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        CodedOutputStream c = CodedOutputStream.newInstance(b);
        c.writeString(4, "OsmSchema-V0.6");
        c.writeString(4, "DenseNodes");
        c.writeString(16, "saarthi-test");
        c.writeInt64(32, 1_760_000_000L);
        c.writeInt64(33, 4936L);
        c.flush();
        return b.toByteArray();
    }

    private static byte[] primitiveBlock(List<Node> nodes, List<Way> ways, List<Relation> relations) throws IOException {
        List<String> strings = new ArrayList<>();
        strings.add("");
        java.util.function.ToIntFunction<String> idx = s -> {
            int i = strings.indexOf(s);
            if (i < 0) {
                strings.add(s);
                i = strings.size() - 1;
            }
            return i;
        };

        // Dense nodes with delta coding (granularity 100 nanodegrees).
        ByteArrayOutputStream ids = new ByteArrayOutputStream();
        ByteArrayOutputStream lats = new ByteArrayOutputStream();
        ByteArrayOutputStream lons = new ByteArrayOutputStream();
        ByteArrayOutputStream kv = new ByteArrayOutputStream();
        ByteArrayOutputStream versions = new ByteArrayOutputStream();
        ByteArrayOutputStream timestamps = new ByteArrayOutputStream();
        CodedOutputStream cIds = CodedOutputStream.newInstance(ids);
        CodedOutputStream cLat = CodedOutputStream.newInstance(lats);
        CodedOutputStream cLon = CodedOutputStream.newInstance(lons);
        CodedOutputStream cKv = CodedOutputStream.newInstance(kv);
        CodedOutputStream cVer = CodedOutputStream.newInstance(versions);
        CodedOutputStream cTs = CodedOutputStream.newInstance(timestamps);
        long pid = 0;
        long plat = 0;
        long plon = 0;
        long pts = 0;
        for (Node n : nodes) {
            long lat = Math.round(n.lat() * 1e7);
            long lon = Math.round(n.lon() * 1e7);
            cIds.writeSInt64NoTag(n.id() - pid);
            cLat.writeSInt64NoTag(lat - plat);
            cLon.writeSInt64NoTag(lon - plon);
            cVer.writeInt32NoTag(3);
            long ts = 1_700_000_000L;
            cTs.writeSInt64NoTag(ts - pts);
            pid = n.id();
            plat = lat;
            plon = lon;
            pts = ts;
            for (int i = 0; i < n.tags().length; i += 2) {
                cKv.writeInt32NoTag(idx.applyAsInt(n.tags()[i]));
                cKv.writeInt32NoTag(idx.applyAsInt(n.tags()[i + 1]));
            }
            cKv.writeInt32NoTag(0);
        }
        for (CodedOutputStream c : List.of(cIds, cLat, cLon, cKv, cVer, cTs)) {
            c.flush();
        }
        ByteArrayOutputStream denseInfo = new ByteArrayOutputStream();
        CodedOutputStream di = CodedOutputStream.newInstance(denseInfo);
        di.writeByteArray(1, versions.toByteArray());
        di.writeByteArray(2, timestamps.toByteArray());
        di.flush();
        ByteArrayOutputStream dense = new ByteArrayOutputStream();
        CodedOutputStream d = CodedOutputStream.newInstance(dense);
        d.writeByteArray(1, ids.toByteArray());
        d.writeByteArray(5, denseInfo.toByteArray());
        d.writeByteArray(8, lats.toByteArray());
        d.writeByteArray(9, lons.toByteArray());
        d.writeByteArray(10, kv.toByteArray());
        d.flush();

        ByteArrayOutputStream group1 = new ByteArrayOutputStream();
        CodedOutputStream g1 = CodedOutputStream.newInstance(group1);
        g1.writeByteArray(2, dense.toByteArray());
        g1.flush();

        ByteArrayOutputStream group2 = new ByteArrayOutputStream();
        CodedOutputStream g2 = CodedOutputStream.newInstance(group2);
        for (Way w : ways) {
            ByteArrayOutputStream keys = new ByteArrayOutputStream();
            ByteArrayOutputStream vals = new ByteArrayOutputStream();
            ByteArrayOutputStream refs = new ByteArrayOutputStream();
            CodedOutputStream ck = CodedOutputStream.newInstance(keys);
            CodedOutputStream cv = CodedOutputStream.newInstance(vals);
            CodedOutputStream cr = CodedOutputStream.newInstance(refs);
            for (int i = 0; i < w.tags().length; i += 2) {
                ck.writeUInt32NoTag(idx.applyAsInt(w.tags()[i]));
                cv.writeUInt32NoTag(idx.applyAsInt(w.tags()[i + 1]));
            }
            long prev = 0;
            for (long r : w.refs()) {
                cr.writeSInt64NoTag(r - prev);
                prev = r;
            }
            ck.flush();
            cv.flush();
            cr.flush();
            ByteArrayOutputStream info = new ByteArrayOutputStream();
            CodedOutputStream ci = CodedOutputStream.newInstance(info);
            ci.writeInt32(1, 7);
            ci.writeInt64(2, 1_710_000_000L);
            ci.flush();
            ByteArrayOutputStream way = new ByteArrayOutputStream();
            CodedOutputStream cw = CodedOutputStream.newInstance(way);
            cw.writeInt64(1, w.id());
            cw.writeByteArray(2, keys.toByteArray());
            cw.writeByteArray(3, vals.toByteArray());
            cw.writeByteArray(4, info.toByteArray());
            cw.writeByteArray(8, refs.toByteArray());
            cw.flush();
            g2.writeByteArray(3, way.toByteArray());
        }
        g2.flush();

        ByteArrayOutputStream group3 = new ByteArrayOutputStream();
        CodedOutputStream g3 = CodedOutputStream.newInstance(group3);
        for (Relation r : relations) {
            ByteArrayOutputStream keys = new ByteArrayOutputStream();
            ByteArrayOutputStream vals = new ByteArrayOutputStream();
            ByteArrayOutputStream roles = new ByteArrayOutputStream();
            ByteArrayOutputStream memids = new ByteArrayOutputStream();
            ByteArrayOutputStream types = new ByteArrayOutputStream();
            CodedOutputStream ck = CodedOutputStream.newInstance(keys);
            CodedOutputStream cv = CodedOutputStream.newInstance(vals);
            CodedOutputStream cro = CodedOutputStream.newInstance(roles);
            CodedOutputStream cm = CodedOutputStream.newInstance(memids);
            CodedOutputStream cty = CodedOutputStream.newInstance(types);
            for (int i = 0; i < r.tags().length; i += 2) {
                ck.writeUInt32NoTag(idx.applyAsInt(r.tags()[i]));
                cv.writeUInt32NoTag(idx.applyAsInt(r.tags()[i + 1]));
            }
            long prev = 0;
            for (int i = 0; i < r.members().length; i++) {
                cro.writeInt32NoTag(idx.applyAsInt(r.roles()[i]));
                cm.writeSInt64NoTag(r.members()[i][1] - prev);
                prev = r.members()[i][1];
                cty.writeInt32NoTag((int) r.members()[i][0]);
            }
            for (CodedOutputStream c : List.of(ck, cv, cro, cm, cty)) {
                c.flush();
            }
            ByteArrayOutputStream rel = new ByteArrayOutputStream();
            CodedOutputStream cr = CodedOutputStream.newInstance(rel);
            cr.writeInt64(1, r.id());
            cr.writeByteArray(2, keys.toByteArray());
            cr.writeByteArray(3, vals.toByteArray());
            cr.writeByteArray(8, roles.toByteArray());
            cr.writeByteArray(9, memids.toByteArray());
            cr.writeByteArray(10, types.toByteArray());
            cr.flush();
            g3.writeByteArray(4, rel.toByteArray());
        }
        g3.flush();

        ByteArrayOutputStream table = new ByteArrayOutputStream();
        CodedOutputStream ct = CodedOutputStream.newInstance(table);
        for (String s : strings) {
            ct.writeBytes(1, ByteString.copyFrom(s, StandardCharsets.UTF_8));
        }
        ct.flush();
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        CodedOutputStream cb = CodedOutputStream.newInstance(block);
        cb.writeByteArray(1, table.toByteArray());
        cb.writeByteArray(2, group1.toByteArray());
        cb.writeByteArray(2, group2.toByteArray());
        if (!relations.isEmpty()) {
            cb.writeByteArray(2, group3.toByteArray());
        }
        cb.flush();
        return block.toByteArray();
    }

    private static void writeBlob(DataOutputStream out, String type, byte[] payload) throws IOException {
        Deflater deflater = new Deflater();
        deflater.setInput(payload);
        deflater.finish();
        byte[] buf = new byte[payload.length + 1024];
        int len = deflater.deflate(buf);
        deflater.end();
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        CodedOutputStream cb = CodedOutputStream.newInstance(blob);
        cb.writeInt32(2, payload.length);
        cb.writeByteArray(3, java.util.Arrays.copyOf(buf, len));
        cb.flush();
        byte[] blobBytes = blob.toByteArray();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        CodedOutputStream ch = CodedOutputStream.newInstance(header);
        ch.writeString(1, type);
        ch.writeInt32(3, blobBytes.length);
        ch.flush();
        byte[] headerBytes = header.toByteArray();
        out.writeInt(headerBytes.length);
        out.write(headerBytes);
        out.write(blobBytes);
    }
}

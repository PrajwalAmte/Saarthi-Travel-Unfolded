import { ImageResponse } from "next/og";

export const size = { width: 180, height: 180 };
export const contentType = "image/png";

/** Home-screen icon for iPhones and iPads (they need a PNG, not an SVG). Same mark as the logo. */
export default function AppleIcon() {
  return new ImageResponse(
    (
      <div style={{ display: "flex", width: "100%", height: "100%", background: "#0e5b57" }}>
        <svg width="180" height="180" viewBox="0 0 32 32">
          <circle cx="24" cy="8.5" r="2.4" fill="#faf8f4" opacity="0.85" />
          <path d="M3 26 L12 10.5 L17.3 19.2 L20.8 14.2 L29 26 Z" fill="#faf8f4" />
          <path d="M11.2 26 C13.6 22.6 19.8 22.4 17.4 19.1 C16.4 17.7 17.6 16.6 16.9 15.2 L17.9 15.2 C18.7 16.7 17.6 18 18.7 19.4 C21.6 22.9 16.6 23.4 20.6 26 Z" fill="#0e5b57" />
        </svg>
      </div>
    ),
    size,
  );
}

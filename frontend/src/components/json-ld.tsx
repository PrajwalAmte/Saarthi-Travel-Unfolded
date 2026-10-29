import { jsonLdString } from "@/lib/seo";

/** Structured data for search engines; a data block, never executed. */
export function JsonLd({ data }: { data: unknown }) {
  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: jsonLdString(data) }} />;
}

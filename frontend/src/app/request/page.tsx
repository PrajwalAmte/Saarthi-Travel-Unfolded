import { RequestForm } from "@/components/request-form";

export const metadata = { title: "Request a destination" };

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

export default async function RequestPage({ searchParams }: Props) {
  const sp = await searchParams;
  const q = (Array.isArray(sp.q) ? sp.q[0] : sp.q) ?? "";
  return (
    <div className="container-page max-w-2xl py-10">
      <h1 className="text-3xl font-semibold sm:text-4xl">Request a destination</h1>
      <p className="mt-3 text-ink-2">
        Tell us which place you want a guide for. We look it up in Wikidata, add it to the index and ingest open guide content
        if a source covers it. A guide is only published once it passes the quality checks, so some requests stay indexed
        without a guide until contributors add to it.
      </p>
      <div className="mt-6">
        <RequestForm initialQuery={q} />
      </div>
    </div>
  );
}

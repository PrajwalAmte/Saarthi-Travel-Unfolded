import Link from "next/link";
import { notFound } from "next/navigation";
import { ContributeForm } from "@/components/contribute-form";
import { getDestination } from "@/lib/destination";

export const dynamic = "force-dynamic";
export const metadata = { title: "Contribute" };

export default async function ContributePage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const d = await getDestination(slug);
  if (!d) notFound();
  return (
    <div className="container-page max-w-2xl py-10">
      <p className="text-sm text-muted">
        <Link href={`/d/${d.slug}`} className="underline">
          {d.name}
        </Link>
      </p>
      <h1 className="mt-1 text-3xl font-semibold sm:text-4xl">Add to the {d.name} guide</h1>
      <p className="mt-3 text-ink-2">
        Share a place or a tip from first-hand knowledge. New contributors’ submissions are reviewed before they appear, and
        everything you add is shown with the date you reported it.
      </p>
      <div className="mt-6">
        <ContributeForm destinationId={d.id} slug={d.slug} name={d.name} />
      </div>
    </div>
  );
}

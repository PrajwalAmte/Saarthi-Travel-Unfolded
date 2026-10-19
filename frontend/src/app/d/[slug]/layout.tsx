import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { DestinationHeader } from "@/components/guide/destination-header";
import { GuideTabs } from "@/components/guide/guide-tabs";
import { IndexedOnly } from "@/components/guide/indexed-only";
import { getDestination } from "@/lib/destination";

type Props = { children: React.ReactNode; params: Promise<{ slug: string }> };

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const { slug } = await params;
  const d = await getDestination(slug);
  if (!d) return { title: "Destination not found" };
  return {
    title: `${d.name} guide`,
    description: d.summary?.text.slice(0, 160) ?? `${d.name}: ${d.description ?? "destination in India"}`,
  };
}

export default async function DestinationLayout({ children, params }: Props) {
  const { slug } = await params;
  const d = await getDestination(slug);
  if (!d) notFound();
  return (
    <div className="container-page">
      <DestinationHeader d={d} />
      {d.guideAvailable ? (
        <>
          <Suspense fallback={<div className="h-11 border-b border-line" />}>
            <GuideTabs slug={d.slug} counts={d.sectionCounts} />
          </Suspense>
          <div className="pt-6">{children}</div>
        </>
      ) : (
        <>
          <IndexedOnly d={d} />
          <div className="pb-10 pt-8">{children}</div>
        </>
      )}
    </div>
  );
}

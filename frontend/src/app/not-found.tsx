import Link from "next/link";

export default function NotFound() {
  return (
    <div className="container-page max-w-xl py-16">
      <h1 className="text-3xl font-semibold">Not found</h1>
      <p className="mt-3 text-ink-2">This page or destination does not exist in the index.</p>
      <div className="mt-6 flex gap-4 text-sm">
        <Link href="/search" className="text-accent underline">
          Search destinations
        </Link>
        <Link href="/request" className="text-accent underline">
          Request a destination
        </Link>
      </div>
    </div>
  );
}

import { SignInForm } from "@/components/signin-form";

export const metadata = { title: "Contributor sign-in" };

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

export default async function SignInPage({ searchParams }: Props) {
  const sp = await searchParams;
  const next = typeof sp.next === "string" && sp.next.startsWith("/") && !sp.next.startsWith("//") ? sp.next : "/account";
  const error = sp.error === "oauth" ? "Sign-in with that provider did not complete. Please try again." : null;
  return (
    <div className="container-page max-w-xl py-10">
      <h1 className="text-3xl font-semibold sm:text-4xl">Become a contributor</h1>
      <p className="mt-3 text-ink-2">
        Reading the guides never needs an account. Signing in lets you confirm facts, correct prices and hours, report closures
        and add places you know. We keep only a display name and a verified email.
      </p>
      <div className="mt-6">
        <SignInForm next={next} initialError={error} />
      </div>
    </div>
  );
}

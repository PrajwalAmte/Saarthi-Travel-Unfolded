import { VerifyToken } from "@/components/verify-token";

export const metadata = { title: "Signing in" };

export default function VerifyPage() {
  return (
    <div className="container-page max-w-xl py-12">
      <h1 className="text-3xl font-semibold">Signing you in</h1>
      <div className="mt-6">
        <VerifyToken />
      </div>
    </div>
  );
}

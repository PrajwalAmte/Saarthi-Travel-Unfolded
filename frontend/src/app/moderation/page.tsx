import { ModerationQueue } from "@/components/moderation-queue";
import { NOINDEX } from "@/lib/seo";

export const metadata = { title: "Review queue", robots: NOINDEX };

export default function ModerationPage() {
  return (
    <div className="container-page py-10">
      <ModerationQueue />
    </div>
  );
}

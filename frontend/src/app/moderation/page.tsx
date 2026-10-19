import { ModerationQueue } from "@/components/moderation-queue";

export const metadata = { title: "Review queue" };

export default function ModerationPage() {
  return (
    <div className="container-page py-10">
      <ModerationQueue />
    </div>
  );
}

import { AdminConsole } from "@/components/admin-console";
import { NOINDEX } from "@/lib/seo";

export const metadata = { title: "Operations", robots: NOINDEX };

export default function AdminPage() {
  return (
    <div className="container-page py-10">
      <AdminConsole />
    </div>
  );
}

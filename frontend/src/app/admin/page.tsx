import { AdminConsole } from "@/components/admin-console";

export const metadata = { title: "Operations" };

export default function AdminPage() {
  return (
    <div className="container-page py-10">
      <AdminConsole />
    </div>
  );
}

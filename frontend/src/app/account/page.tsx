import { AccountView } from "@/components/account-view";

export const metadata = { title: "Your contributions" };

export default function AccountPage() {
  return (
    <div className="container-page py-10">
      <AccountView />
    </div>
  );
}

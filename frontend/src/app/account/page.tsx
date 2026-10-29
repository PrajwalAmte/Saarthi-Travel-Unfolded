import { AccountView } from "@/components/account-view";
import { NOINDEX } from "@/lib/seo";

export const metadata = { title: "Your contributions", robots: NOINDEX };

export default function AccountPage() {
  return (
    <div className="container-page py-10">
      <AccountView />
    </div>
  );
}

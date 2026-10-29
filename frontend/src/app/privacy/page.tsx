export const metadata = { title: "Privacy and data retention", alternates: { canonical: "/privacy" } };

export default function PrivacyPage() {
  return (
    <article className="container-page max-w-3xl py-10 text-ink-2 [&_h2]:mt-10 [&_h2]:text-2xl [&_h2]:font-semibold [&_h2]:text-ink [&_li]:mt-1 [&_p]:mt-3 [&_ul]:mt-3 [&_ul]:list-disc [&_ul]:pl-5">
      <h1 className="text-3xl font-semibold text-ink sm:text-4xl">Privacy and data retention</h1>
      <h2>Readers</h2>
      <p>
        Reading, searching and requesting destinations need no account. We do not use advertising or third-party analytics.
        Your preferences live in the page address so guides can be shared. The server keeps short-lived, in-memory counters per
        network address for rate limiting; they are not written to the database.
      </p>
      <h2>Contributors</h2>
      <ul>
        <li>We store a display name, a verified email address (or an OAuth provider identifier) and your contribution history.</li>
        <li>Sign-in links are single-use, expire after 15 minutes, and only a hash of the token is stored.</li>
        <li>Your contributions are credited by display name or anonymously, as you choose in your account.</li>
        <li>Sessions are stored server-side and expire after 30 days of inactivity.</li>
      </ul>
      <h2>Deletion</h2>
      <ul>
        <li>Withdraw any pending contribution from your account page at any time.</li>
        <li>Delete your account from your account page: your email, display name and sign-in identities are removed immediately, pending contributions are withdrawn and you are signed out.</li>
        <li>
          Published contributions are licensed under CC BY-SA 4.0 and stay in the guide, attributed to an anonymous former
          contributor. You can also remove your name from an individual published contribution.
        </li>
        <li>The audit log keeps records of actions for accountability; after deletion they no longer identify you.</li>
      </ul>
      <h2>Third-party data</h2>
      <p>
        Guide content is stored as extracted facts with links to the source revision. We do not store raw third-party posts or
        their authors’ usernames. Images are linked from Wikimedia Commons with their author and licence.
      </p>
    </article>
  );
}

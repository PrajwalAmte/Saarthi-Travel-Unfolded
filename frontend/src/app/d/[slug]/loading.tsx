export default function Loading() {
  return (
    <div className="space-y-6 py-4" aria-busy="true" aria-label="Loading guide">
      <div className="skeleton h-14 w-full" />
      {[0, 1, 2].map((i) => (
        <div key={i} className="space-y-2 border-b border-line pb-6">
          <div className="skeleton h-6 w-1/3" />
          <div className="skeleton h-4 w-2/3" />
          <div className="skeleton h-4 w-full" />
          <div className="skeleton h-4 w-5/6" />
        </div>
      ))}
    </div>
  );
}

import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

// next/navigation is only available inside the Next.js runtime; tests get controllable fakes.
export const routerMock = { push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() };
export const navigationState = { pathname: "/", search: "" };

vi.mock("next/navigation", () => ({
  useRouter: () => routerMock,
  usePathname: () => navigationState.pathname,
  useSearchParams: () => new URLSearchParams(navigationState.search),
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));

vi.mock("next/link", async () => {
  const React = await import("react");
  return {
    default: React.forwardRef<HTMLAnchorElement, React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>(
      function Link({ href, children, ...rest }, ref) {
        return React.createElement("a", { ...rest, href, ref }, children);
      },
    ),
  };
});

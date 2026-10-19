import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { StillTrue } from "@/components/guide/still-true";
import { RequestForm } from "@/components/request-form";
import type { SessionView } from "@/lib/types";
import { axeViolations } from "./a11y";
import { fact } from "./fixtures";
import { routerMock } from "./setup";

const sessionState: { session: SessionView | null } = { session: null };

vi.mock("@/components/session-provider", () => ({
  useSession: () => ({ session: sessionState.session, loading: false, refresh: vi.fn(), signOut: vi.fn() }),
}));

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("RequestForm", () => {
  beforeEach(() => {
    routerMock.push.mockReset();
    document.cookie = "XSRF-TOKEN=test-token";
  });

  it("validates input before calling the API", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    render(<RequestForm />);
    await userEvent.click(screen.getByRole("button", { name: "Request this destination" }));
    expect(await screen.findByText("Enter at least 2 characters")).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("submits with the CSRF header and opens the tracking page", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(json({ trackingCode: "abcdefghjk" }, 201));
    render(<RequestForm initialQuery="Hampi" />);
    await userEvent.click(screen.getByRole("button", { name: "Request this destination" }));
    await waitFor(() => expect(routerMock.push).toHaveBeenCalledWith("/requests/abcdefghjk"));
    const [url, init] = fetchSpy.mock.calls[0]!;
    expect(url).toBe("/api/requests");
    expect((init as RequestInit).headers).toMatchObject({ "X-XSRF-TOKEN": "test-token" });
    expect(JSON.parse(String((init as RequestInit).body))).toEqual({ query: "Hampi" });
  });

  it("shows API errors", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(json({ status: 429, code: "rate_limited", detail: "Too many requests." }, 429));
    render(<RequestForm initialQuery="Hampi" />);
    await userEvent.click(screen.getByRole("button", { name: "Request this destination" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many requests.");
  });

  it("is accessible", async () => {
    const { container } = render(<RequestForm />);
    expect(await axeViolations(container)).toEqual([]);
  });
});

describe("Still true?", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token";
  });

  it("asks signed-out readers to sign in instead of counting anything locally", async () => {
    sessionState.session = { authenticated: false, user: null, emailSignInAvailable: true, oauthProviders: [], csrfToken: null };
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    render(<StillTrue fact={fact()} destinationId="d" />);
    await userEvent.click(screen.getByRole("button", { name: /Still true\?/ }));
    expect(await screen.findByRole("link", { name: "Sign in to contribute" })).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("creates a verification record through the API when confirmed", async () => {
    sessionState.session = {
      authenticated: true,
      user: { id: "u", displayName: "Tester", tier: "contributor", admin: false, licenceAccepted: true },
      emailSignInAvailable: true,
      oauthProviders: [],
      csrfToken: null,
    };
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(json({ id: "v", status: "accepted", message: "Thanks! Your confirmation is recorded." }, 201));
    render(<StillTrue fact={fact()} destinationId="d" />);
    await userEvent.click(screen.getByRole("button", { name: /Still true\?/ }));
    await userEvent.click(await screen.findByText("Yes, still true"));
    await userEvent.click(screen.getByRole("button", { name: "Submit" }));
    expect(await screen.findByText("Thanks! Your confirmation is recorded.")).toBeInTheDocument();
    const [url, init] = fetchSpy.mock.calls[0]!;
    expect(url).toBe("/api/facts/00000000-0000-0000-0000-000000000001/verifications");
    expect(JSON.parse(String((init as RequestInit).body))).toEqual({ verdict: "confirm" });
    expect(routerMock.refresh).toHaveBeenCalled();
  });

  it("validates corrections before submitting", async () => {
    sessionState.session = {
      authenticated: true,
      user: { id: "u", displayName: "Tester", tier: "contributor", admin: false, licenceAccepted: true },
      emailSignInAvailable: true,
      oauthProviders: [],
      csrfToken: null,
    };
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    render(<StillTrue fact={fact({ kind: "price" })} destinationId="d" />);
    await userEvent.click(screen.getByRole("button", { name: /Still true\?/ }));
    await userEvent.click(await screen.findByText("It has changed"));
    await userEvent.click(screen.getByRole("button", { name: "Submit" }));
    expect(await screen.findByText("Enter the current value")).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});

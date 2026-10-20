import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ServiceWaking } from "@/components/service-waking";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

describe("ServiceWaking", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    refresh.mockClear();
  });
  afterEach(() => vi.useRealTimers());

  it("explains the wait and refreshes the page by itself, then stops", () => {
    render(<ServiceWaking />);
    expect(screen.getByRole("status")).toHaveTextContent("The guide service is waking up. Try again in a minute.");
    expect(screen.getByText("This page will refresh by itself.")).toBeInTheDocument();
    for (let i = 0; i < 12; i++) {
      act(() => {
        vi.advanceTimersByTime(20_000);
      });
    }
    expect(refresh).toHaveBeenCalledTimes(9);
    expect(screen.getByText(/taking longer than usual/)).toBeInTheDocument();
  });
});

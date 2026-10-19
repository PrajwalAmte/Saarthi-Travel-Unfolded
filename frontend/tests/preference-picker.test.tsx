import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import * as React from "react";
import { describe, expect, it } from "vitest";
import { PreferencePicker } from "@/components/preference-picker";
import { preferencesToParams, type Preferences } from "@/lib/preferences";
import { axeViolations } from "./a11y";

function Harness({ onChange }: { onChange: (p: Preferences) => void }) {
  const [value, setValue] = React.useState<Preferences>({ interests: [] });
  return (
    <PreferencePicker
      value={value}
      onChange={(p) => {
        setValue(p);
        onChange(p);
      }}
    />
  );
}

describe("PreferencePicker", () => {
  it("translates selections into the API's ranking parameters", async () => {
    let latest: Preferences = { interests: [] };
    render(<Harness onChange={(p) => (latest = p)} />);
    await userEvent.click(screen.getByRole("button", { name: "Regional food" }));
    await userEvent.click(screen.getByRole("button", { name: "Budget-friendly" }));
    await userEvent.click(screen.getByRole("button", { name: "Culture" }));
    await userEvent.click(screen.getByRole("button", { name: "Nature" }));
    expect(screen.getByRole("button", { name: "Regional food" })).toHaveAttribute("aria-pressed", "true");
    expect(preferencesToParams(latest).toString()).toBe("food=regional&budget=budget&interests=culture%2Cnature");
  });

  it("toggles a choice off and limits interests to three", async () => {
    let latest: Preferences = { interests: [] };
    render(<Harness onChange={(p) => (latest = p)} />);
    await userEvent.click(screen.getByRole("button", { name: "Premium" }));
    await userEvent.click(screen.getByRole("button", { name: "Premium" }));
    expect(latest.budget).toBeUndefined();
    for (const name of ["Food", "Culture", "Nature"]) {
      await userEvent.click(screen.getByRole("button", { name }));
    }
    expect(screen.getByRole("button", { name: "Nightlife" })).toBeDisabled();
    expect(latest.interests).toEqual(["food", "culture", "nature"]);
  });

  it("is accessible", async () => {
    const { container } = render(<Harness onChange={() => undefined} />);
    expect(await axeViolations(container)).toEqual([]);
  });
});

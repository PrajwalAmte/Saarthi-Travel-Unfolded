import { z } from "zod";

const optionalUrl = z
  .string()
  .trim()
  .max(2000)
  .refine((v) => v === "" || /^https?:\/\/[^\s/$.?#].[^\s]*$/i.test(v), "Enter a full link starting with http:// or https://")
  .optional()
  .transform((v) => (v ? v : undefined));

const optionalText = (max: number) =>
  z
    .string()
    .trim()
    .max(max, `Keep this under ${max} characters`)
    .optional()
    .transform((v) => (v ? v : undefined));

export const denySchema = z.object({
  note: optionalText(500),
  evidenceUrl: optionalUrl,
});

export const correctionSchema = z
  .object({
    value: z.string().trim().min(1, "Enter the current value").max(1000, "Keep this under 1000 characters"),
    note: optionalText(1000),
    evidenceUrl: optionalUrl,
    conflictOfInterest: z.boolean().default(false),
    coiDetails: optionalText(500),
  })
  .refine((v) => !v.conflictOfInterest || Boolean(v.coiDetails), {
    path: ["coiDetails"],
    message: "Describe your connection to this place",
  });

const optionalEmail = z.preprocess(
  (v) => (typeof v === "string" && v.trim() === "" ? undefined : v),
  z.string().trim().toLowerCase().email("Enter a valid email address, or leave it blank").max(254).optional(),
);

export const requestSchema = z.object({
  query: z
    .string()
    .trim()
    .min(2, "Enter at least 2 characters")
    .max(120, "Keep this under 120 characters")
    .refine((v) => /\p{L}/u.test(v), "Enter a place name"),
  state: optionalText(60),
  email: optionalEmail,
});

export const notifySchema = z.object({
  email: z.string().trim().toLowerCase().min(1, "Enter your email address").email("Enter a valid email address").max(254),
});

export const emailSignInSchema = z.object({
  email: z.string().trim().toLowerCase().email("Enter a valid email address").max(254),
  displayName: optionalText(60),
});

export const newPlaceSchema = z
  .object({
    section: z.enum(["eat", "drink", "stay", "see", "do", "buy"]),
    name: z.string().trim().min(2, "Enter the name").max(120),
    description: z.string().trim().min(20, "Describe it in at least 20 characters").max(1500),
    address: optionalText(300),
    price: optionalText(200),
    hours: optionalText(200),
    whatToOrder: optionalText(300),
    website: optionalUrl,
    note: optionalText(1000),
    evidenceUrl: optionalUrl,
    conflictOfInterest: z.boolean().default(false),
    coiDetails: optionalText(500),
  })
  .refine((v) => !v.conflictOfInterest || Boolean(v.coiDetails), {
    path: ["coiDetails"],
    message: "Describe your connection to this place",
  });

export const newTipSchema = z.object({
  section: z.enum(["getting_around", "tips", "neighbourhood", "eat", "stay", "practical"]),
  heading: optionalText(120),
  text: z.string().trim().min(20, "Write at least 20 characters").max(1500),
  note: optionalText(1000),
  evidenceUrl: optionalUrl,
});

export const closureSchema = z.object({
  closure: z.enum(["permanent", "temporary"]),
  details: optionalText(500),
  evidenceUrl: optionalUrl,
});

export const incorrectSchema = z.object({
  explanation: z.string().trim().min(10, "Explain what is wrong in at least 10 characters").max(1000),
  evidenceUrl: optionalUrl,
});

export const flagSchema = z.object({
  reason: z.enum(["spam", "self_promotion", "offensive", "misleading", "other"]),
  details: optionalText(1000),
});

/** Maps zod issues to a field -> message record for form display. */
export function fieldErrors(error: z.ZodError): Record<string, string> {
  const out: Record<string, string> = {};
  for (const issue of error.issues) {
    const key = issue.path.join(".") || "form";
    if (!out[key]) out[key] = issue.message;
  }
  return out;
}

/** Words that mark a search as a question rather than a place name (kept in step with the API's own check). */
const QUESTION =
  /\b(near|nearby|around|within|from|hours?|hrs?|km|weekend|trip|trips|places?|getaways?|best|where|which|what|beach(es)?|hill|hills|waterfalls?|wildlife|treks?|trekking|heritage|pilgrimage|cool|cold|warm|rain|monsoon|january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\b|\?/i;

export function looksLikeQuestion(q: string): boolean {
  const t = q.trim();
  return t.split(/\s+/).length >= 4 || QUESTION.test(t);
}

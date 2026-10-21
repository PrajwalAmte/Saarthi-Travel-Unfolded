/* Types mirroring the backend API (see /v3/api-docs). */

export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
};

export type CoverageStatus = "indexed" | "building" | "usable" | "well_covered" | "needs_refresh";

export type SearchHit = {
  id: string;
  slug: string;
  name: string;
  kind: string;
  description: string | null;
  matchedAlias: string | null;
  matchType: "exact" | "prefix" | "fuzzy";
  score: number;
  stateName: string | null;
  districtName: string | null;
  parentCityName: string | null;
  parentCitySlug: string | null;
  coverageStatus: CoverageStatus | null;
  latitude: number | null;
  longitude: number | null;
  hasGuide: boolean;
  guideSlug: string;
};

export type SearchResult = { query: string; results: SearchHit[] };

export type Evidence = {
  sourceId: string;
  sourceName: string;
  sourceCategory: string;
  licenceName: string;
  licenceUrl: string | null;
  url: string | null;
  permalink: string | null;
  title: string | null;
  excerpt: string | null;
  extractionMethod: string;
  sourceRevision: string | null;
  sourceRevisionAt: string | null;
  observedAt: string | null;
  retrievedAt: string;
  contributorName: string | null;
};

export type Volatility = "stable" | "semi_stable" | "volatile";
export type Freshness = "current" | "due" | "unknown";
export type VerificationStatus =
  | "source_backed"
  | "community_verified"
  | "community_reported"
  | "needs_reverification"
  | "conflicting_reports";

export type Fact = {
  id: string;
  entryId: string | null;
  kind: string;
  section: string;
  heading: string | null;
  text: string;
  value: Record<string, unknown> | null;
  volatility: Volatility;
  origin: "ingested" | "community";
  observedAt: string | null;
  observedBasis: string;
  sourceRefreshedAt: string | null;
  lastVerifiedAt: string | null;
  reviewDueAt: string | null;
  freshness: Freshness;
  verificationStatus: VerificationStatus;
  verificationCount: number;
  denialCount: number;
  pendingCorrections: number;
  ownerSubmitted: boolean;
  evidence: Evidence[];
};

export type Label = { code: string; text: string; basis: string | null };
export type Reason = { code: string; text: string };

export type Entry = {
  id: string;
  section: string;
  subsection: string | null;
  area: string | null;
  title: string;
  altTitle: string | null;
  latitude: number | null;
  longitude: number | null;
  distanceKm: number | null;
  origin: "ingested" | "community";
  labels: Label[];
  description: Fact | null;
  details: Fact[];
  mapUrl: string;
  score: number | null;
  reasons: Reason[];
};

export type CategoryCount = { key: string; label: string; count: number };
export type ExternalLink = { label: string; url: string; note: string };

export type SectionView = {
  section: "eat" | "stay" | "explore";
  categories: CategoryCount[];
  entries: Page<Entry>;
  totalPublished: number;
  preferencesApplied: boolean;
  personalizationApplied: boolean;
  personalizationNote: string | null;
  excludedByConstraints: number;
  constraintNotes: string[];
  notes: Fact[];
  specialities: Fact[];
  externalLinks: ExternalLink[];
};

export type FactGroup = { key: string; title: string; facts: Fact[] };
export type ClimateMonth = {
  month: number;
  meanMaxC: number | null;
  meanMinC: number | null;
  precipitationMm: number | null;
  rainyDays: number | null;
};
export type ClimateView = { fact: Fact; period: string | null; months: ClimateMonth[] };
export type FactSectionView = { section: string; groups: FactGroup[]; totalFacts: number; climate: ClimateView | null };

export type NeighbourhoodView = { entry: Entry; localities: string[] };
export type AreaCount = { area: string; sections: Record<string, number>; total: number };
export type NeighbourhoodSectionView = {
  neighbourhoods: NeighbourhoodView[];
  stayNotes: Fact[];
  orientation: Fact[];
  listingAreas: AreaCount[];
};

export type BudgetRange = {
  category: string;
  label: string;
  tier: string | null;
  low: number | null;
  median: number | null;
  high: number | null;
  observations: number;
  earliestObserved: string | null;
  latestObserved: string | null;
  needingReverification: number;
  unit: string;
  factIds: string[];
};
export type BudgetView = {
  currency: string;
  ranges: BudgetRange[];
  assumptions: string[];
  priceFactsConsidered: number;
  priceFactsWithoutAmount: number;
  emptyReason: string | null;
};

export type CoverageCheck = { rule: string; passed: boolean; detail: string };
export type CoverageView = {
  status: CoverageStatus;
  label: string;
  explanation: string;
  checks: CoverageCheck[];
  stats: Record<string, unknown>;
  computedAt: string | null;
  graduatedAt: string | null;
};

export type SourceRef = {
  id: string;
  name: string;
  category: string;
  licenceName: string;
  licenceUrl: string | null;
  attribution: string;
  baseUrl: string | null;
};

export type DestinationDetail = {
  id: string;
  slug: string;
  name: string;
  kind: string;
  description: string | null;
  latitude: number | null;
  longitude: number | null;
  population: number | null;
  wikidataId: string | null;
  wikidataUrl: string | null;
  wikivoyageUrl: string | null;
  parents: { relation: string; slug: string; name: string; kind: string }[];
  aliases: { alias: string; language: string; type: string }[];
  image: {
    url: string;
    width: number | null;
    height: number | null;
    pageUrl: string;
    artist: string | null;
    licenceName: string;
    licenceUrl: string | null;
    sourceName: string;
  } | null;
  coverage: CoverageView;
  summary: Fact | null;
  sectionCounts: Record<string, number>;
  sourcesUsed: SourceRef[];
  lastSourceSyncAt: string | null;
  guideAvailable: boolean;
  childDestinations: { slug: string; name: string; kind: string; coverageStatus: CoverageStatus | null }[];
  recentlyClosed: { id: string; section: string; title: string; reason: string | null; reportedAt: string }[];
};

export type FactDetail = {
  fact: Fact;
  factStatus: string;
  entry: { id: string; title: string; section: string } | null;
  destination: { id: string; slug: string; name: string };
  history: {
    value: string;
    structured: Record<string, unknown> | null;
    observedAt: string | null;
    changeType: string;
    sourceId: string | null;
    recordedAt: string;
  }[];
  verifications: {
    id: string;
    verdict: "confirm" | "deny";
    status: string;
    note: string | null;
    evidenceUrl: string | null;
    contributor: string;
    createdAt: string;
  }[];
  reviewWindowDays: number;
};

export type RequestStatus =
  | "received"
  | "queued"
  | "processing"
  | "indexed_no_guide"
  | "building"
  | "published"
  | "needs_review"
  | "unresolvable";

export type RequestView = {
  trackingCode: string;
  query: string;
  stateHint: string | null;
  status: RequestStatus;
  statusLabel: string;
  statusDetail: string | null;
  demandCount: number;
  destination: { slug: string; name: string; kind: string; coverageStatus: CoverageStatus } | null;
  candidates: { wikidataId: string; label: string | null; description: string | null }[];
  jobStatus: string | null;
  estimate: RequestEstimate | null;
  emailUpdates: boolean;
  firstRequestedAt: string;
  lastRequestedAt: string;
};

export type RequestEstimate = {
  minMinutes: number | null;
  maxMinutes: number | null;
  summary: string;
  basis: string;
  importerRunning: boolean;
  jobsAhead: number;
};

export type SessionUser = { id: string; displayName: string; tier: string; admin: boolean; licenceAccepted: boolean };
export type SessionView = {
  authenticated: boolean;
  user: SessionUser | null;
  emailSignInAvailable: boolean;
  oauthProviders: string[];
  csrfToken: string | null;
};

export type Meta = {
  indexedDestinations: number;
  guidesByCoverage: Record<string, number>;
  emailSignInAvailable: boolean;
  oauthProviders: string[];
  contributorLicence: string;
  reviewWindowsDays: Record<Volatility, number>;
  ingestionEnabled: boolean;
};

export type PublicSource = {
  id: string;
  name: string;
  category: string;
  accessMethod: string;
  baseUrl: string | null;
  licenceName: string;
  licenceUrl: string | null;
  attribution: string;
  shareAlike: boolean;
  usageNotes: string | null;
  rateLimitPolicy: string | null;
  refreshIntervalDays: number;
  enabled: boolean;
  lastFetchAt: string | null;
  lastSuccessAt: string | null;
  lastAttemptFailed: boolean;
  lastErrorAt: string | null;
  lastPublicationAt: string | null;
  nextSyncDueAt: string | null;
  datasetVersion: string | null;
  qualityStatus: "not_evaluated" | "passed" | "warning" | "failed";
  qualityEvaluatedAt: string | null;
  qualityScore: number | null;
  qualityReportId: string | null;
  latestRun: SyncRun | null;
  contributions: { kind: string; label: string; count: number }[];
  factsBacked: number;
};

export type SyncRun = {
  id: string;
  scope: string;
  status: "running" | "succeeded" | "succeeded_with_warnings" | "quality_failed" | "failed";
  stage: string;
  datasetVersion: string | null;
  startedAt: string;
  finishedAt: string | null;
  fetched: number;
  parsed: number;
  rejected: number;
  inserted: number;
  updated: number;
  unchanged: number;
  deleted: number;
  published: number;
  warnings: string[];
};

export type MappedPlace = {
  id: string;
  name: string;
  category: string;
  group: "eat" | "drink" | "stay" | "see" | "do" | "buy" | "transport";
  latitude: number;
  longitude: number;
  distanceKm: number | null;
  wikidataId: string | null;
  details: Record<string, { value: string; sourceId: string }>;
  heritage: string[];
  guideEntryId: string | null;
  sources: { sourceId: string; sourceName: string; externalId: string; url: string | null; datasetVersion: string | null; licence: string }[];
};

export type MappedPlaces = {
  destination: string;
  notice: string;
  countsByGroup: Record<string, number>;
  group: string | null;
  total: number;
  offset: number;
  limit: number;
  places: MappedPlace[];
  sourcesIncluded: string[];
  sourcesWithheld: string[];
  attribution: string[];
};

export type IngestionStatus = {
  sources: PublicSource[];
  jobsByStatus: Record<string, number>;
  lastSuccessfulJobAt: string | null;
  recentFailures: { jobType: string; target: string | null; error: string | null; at: string | null }[];
};

export type Profile = {
  id: string;
  displayName: string;
  email: string | null;
  tier: "contributor" | "trusted" | "steward";
  admin: boolean;
  attribution: "display_name" | "anonymous";
  contributorLicence: string | null;
  contributorLicenceAcceptedAt: string | null;
  createdAt: string;
  contributions: Record<string, number>;
  verificationsAccepted: number;
  verificationsPending: number;
  stewardOf: { slug: string; name: string }[];
  trust: {
    accepted: number;
    rejected: number;
    reverted: number;
    recentRejectedOrReverted: number;
    recentDecided: number;
    actionedFlags: number;
    accountAgeDays: number;
  };
  nextTier: { tier: string; minAccepted: number; maxRejectionRatio: number; minAccountAgeDays: number } | null;
};

export type HistoryItem = {
  id: string;
  type: string;
  status: string;
  destinationSlug: string;
  destinationName: string;
  targetTitle: string | null;
  payload: Record<string, unknown>;
  decisionReason: string | null;
  createdAt: string;
  decidedAt: string | null;
};

export type QueueItem = {
  id: string;
  itemType: "contribution" | "verification" | "abuse_flag";
  itemId: string;
  destinationId: string | null;
  destinationSlug: string | null;
  destinationName: string | null;
  reasons: string[];
  requiresRole: "trusted" | "steward";
  priority: number;
  status: string;
  resolution: string | null;
  createdAt: string;
  resolvedAt: string | null;
  details: Record<string, unknown>;
};

export type JobView = {
  id: string;
  jobType: string;
  sourceId: string | null;
  targetPlaceName: string | null;
  targetPlaceSlug: string | null;
  params: Record<string, unknown>;
  status: string;
  priority: number;
  attempts: number;
  maxAttempts: number;
  nextAttemptAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  lastError: string | null;
  stats: Record<string, unknown>;
  createdBy: string;
  createdAt: string;
};

export type ApiProblem = {
  status: number;
  code?: string;
  detail?: string;
  errors?: { field: string; message: string }[];
  candidates?: { id: string; title: string; section: string }[];
  retryAfterSeconds?: number;
};

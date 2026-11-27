export const runState = {
  cleanupRuns: [],
  cleanupTryRunPolicyId: null,
  cleanupTryRunTrigger: null,
  cleanupTryRunSubmitting: false,
  cleanupRunPollToken: 0,
  activeCleanupRunDetailId: null,
  cleanupRunDetailTrigger: null,
  cleanupRunDetailLoadSequence: 0,
  cleanupRunDetailItemGroups: [],
  cleanupRunDetailItemsLoaded: false,
  cleanupRunDetailView: null,
};

export const CLEANUP_RUN_DEFAULT_PAGE_SIZE = 10;

export const cleanupRunPage = {
  before: 0,
  cursors: [0],
  page: 0,
  size: CLEANUP_RUN_DEFAULT_PAGE_SIZE,
  nextBefore: null
};

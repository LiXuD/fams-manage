process.env.FAMS_TEST_LOSS_CONFIRMATION = "true";
process.env.FAMS_TEST_LOSS_RECOVERY = "true";
process.env.FAMS_TEST_LOSS_CLOSED_DAMAGED = "true";
await import("./runtime-loss-http.mjs");

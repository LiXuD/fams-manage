process.env.FAMS_TEST_LOSS_CONFIRMATION = "true";
process.env.FAMS_TEST_LOSS_RECOVERY = "true";
process.env.FAMS_TEST_LOSS_CLOSED_RETURN = "true";
await import("./runtime-loss-http.mjs");

const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "parent_alice_123";
const BOB_UID = "parent_bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validChildPayload(userId, now = new Date(Date.now() - 2000)) {
  return {
    userId,
    name: "Minh",
    age: 11,
    deviceModel: "Pixel 8a",
    avatarColor: "TEAL",
    dailyLimitMin: 120,
    bedtimeStart: "21:30",
    bedtimeEnd: "06:30",
    downtimePaused: false,
    safeSearchEnabled: true,
    aiProtectionEnabled: true,
    vpnFilterEnabled: true,
    blockAdult: true,
    blockGambling: true,
    blockMalware: true,
    blockSocial: false,
    customBlockedDomains: ["badgame.example"],
    blockedAppPackages: ["com.zhiliaoapp.musically"],
    todayScreenTimeMin: 45,
    blockedAttemptsToday: 2,
    batteryPercent: 84,
    createdAt: now,
    updatedAt: now,
  };
}

function validAlertPayload(userId, childId, now = new Date(Date.now() - 2000)) {
  return {
    userId,
    childId,
    childName: "Minh",
    alertType: "CYBERBULLYING",
    severity: "HIGH",
    appSource: "Messenger",
    redactedSnippet: "Repeated hostile phrasing detected from [REDACTED] (on-device score 0.91)",
    confidence: 0.91,
    acknowledged: false,
    createdAt: now,
    updatedAt: now,
  };
}

function validSummaryPayload(userId, childId, now = new Date(Date.now() - 2000)) {
  return {
    userId,
    childId,
    dateKey: "2026-11-20",
    totalScreenTimeMin: 95,
    lateNightMin: 12,
    blockedAttempts: 3,
    anomalyNote: "Usage within normal 14-day baseline (z = 0.4).",
    topApps: ["YouTube|42", "Roblox|30"],
    createdAt: now,
    updatedAt: now,
  };
}

function validUsageQuotaPayload(userId, now = new Date(Date.now() - 2000)) {
  return {
    userId,
    dateIso: "2026-11-22",
    dailyCloudLimit: 15,
    cloudRequestsUsed: 1,
    chatUsed: 1,
    visionUsed: 0,
    briefsUsed: 0,
    studioUsed: 0,
    audioUsed: 0,
    bonusGraceCredits: 0,
    graceClaimed: false,
    ecoLiteMode: false,
    fallbackCascadeCount: 0,
    createdAt: now,
    updatedAt: now,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users cannot read or write any SafeNest collection", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 2000);
  await assertFails(unauth.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauth.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Intruder",
      childModeEnabled: false,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(
    unauth.collection("users").doc(ALICE_UID).collection("children").get()
  );
  await assertFails(
    unauth.collection("users").doc(ALICE_UID).collection("alerts").get()
  );
});

test("Parent profile: owner CRUD, shadow-field rejection, immutable createdAt, cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 10000);

  // Valid create
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Linh Nguyen",
      pinHash: "a1b2c3d4e5f6",
      childModeEnabled: false,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Partial update allowed fields
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      childModeEnabled: true,
      activeChildId: "child_minh",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject shadow field injection
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Cross-user isolation
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      displayName: "Hijacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Children subcollection: valid create, rule update, telemetry update, and rejection of invalid payloads", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const childRef = aliceDb.collection("users").doc(ALICE_UID).collection("children").doc("child_1");

  await assertSucceeds(childRef.set(validChildPayload(ALICE_UID, created)));

  // List own children succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("children").get()
  );

  // Action 1: Rule update succeeds
  await assertSucceeds(
    childRef.update({
      dailyLimitMin: 90,
      downtimePaused: true,
      blockSocial: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Action 2: Telemetry heartbeat update succeeds
  await assertSucceeds(
    childRef.update({
      todayScreenTimeMin: 68,
      blockedAttemptsToday: 4,
      batteryPercent: 72,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject @DocumentId 'id' in create payload
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("children").doc("child_2").set({
      id: "child_2",
      ...validChildPayload(ALICE_UID, created),
    })
  );

  // Reject value poisoning (out-of-bounds dailyLimitMin / invalid bedtime)
  await assertFails(
    childRef.update({
      dailyLimitMin: 9999,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    childRef.update({
      bedtimeStart: "99:99",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("children").doc("child_future").set(
      validChildPayload(ALICE_UID, new Date(Date.now() + 120000))
    )
  );

  // Cross-user denial
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("children").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("children").doc("child_1").get()
  );
});

test("Alerts subcollection: create, acknowledge update, immutable fields, and cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const alertRef = aliceDb.collection("users").doc(ALICE_UID).collection("alerts").doc("alert_1");

  await assertSucceeds(alertRef.set(validAlertPayload(ALICE_UID, "child_1", created)));
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("alerts").get()
  );

  // Acknowledge alert succeeds
  await assertSucceeds(
    alertRef.update({
      acknowledged: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject mutating alert snippet or confidence after creation
  await assertFails(
    alertRef.update({
      redactedSnippet: "Tampered text",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject oversized snippet > 140 chars
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("alerts").doc("alert_big").set({
      ...validAlertPayload(ALICE_UID, "child_1", created),
      redactedSnippet: "x".repeat(145),
    })
  );

  // Cross-user denial
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("alerts").get()
  );
});

test("DailySummaries subcollection: create, batched update, and cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const summaryRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("dailySummaries")
    .doc("child_1_2026-11-20");

  await assertSucceeds(summaryRef.set(validSummaryPayload(ALICE_UID, "child_1", created)));
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("dailySummaries").get()
  );

  await assertSucceeds(
    summaryRef.update({
      totalScreenTimeMin: 112,
      lateNightMin: 20,
      blockedAttempts: 5,
      anomalyNote: "Late-night activity rose 42% above baseline.",
      topApps: ["YouTube|55", "Roblox|35"],
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject dateKey tampering
  await assertFails(
    summaryRef.update({
      dateKey: "2026-11-21",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Cross-user denial
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("dailySummaries").get()
  );
});

test("UsageQuota subcollection: create, deduct/refund updates, ecoLite pref update, shadow-field & API-key rejection, cross-user denial", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const dailyRef = aliceDb.collection("users").doc(ALICE_UID).collection("usageQuota").doc("daily");

  // Unauthenticated read/write denied
  await assertFails(
    unauth.collection("users").doc(ALICE_UID).collection("usageQuota").doc("daily").get()
  );

  // Valid create for daily ledger
  await assertSucceeds(dailyRef.set(validUsageQuotaPayload(ALICE_UID, created)));
  await assertSucceeds(dailyRef.get());

  // Action 1: Atomic deduct + grace claim + refund update succeeds
  await assertSucceeds(
    dailyRef.update({
      cloudRequestsUsed: 2,
      visionUsed: 1,
      bonusGraceCredits: 3,
      graceClaimed: true,
      fallbackCascadeCount: 1,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Action 1 (Refund): Decrementing cloudRequestsUsed back to 1 succeeds
  await assertSucceeds(
    dailyRef.update({
      cloudRequestsUsed: 1,
      updatedAt: new Date(Date.now() - 800),
    })
  );

  // Action 2: Eco Lite preference & daily limit update succeeds
  await assertSucceeds(
    dailyRef.update({
      ecoLiteMode: true,
      dailyCloudLimit: 25,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject invalid periodId (only 'daily' or 'monthly' allowed)
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("usageQuota")
      .doc("yearly")
      .set(validUsageQuotaPayload(ALICE_UID, created))
  );

  // Reject shadow field / BYOK API key accidentally written to Firestore
  await assertFails(
    dailyRef.update({
      byokApiKey: "AIzaSecretShouldNeverBeInFirestore",
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Reject negative counter or out-of-bounds bonusGraceCredits (> 15)
  await assertFails(
    dailyRef.update({
      cloudRequestsUsed: -1,
      updatedAt: new Date(Date.now() - 400),
    })
  );
  await assertFails(
    dailyRef.update({
      bonusGraceCredits: 99,
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Reject immutable createdAt tampering
  await assertFails(
    dailyRef.update({
      createdAt: new Date(Date.now() - 300),
      updatedAt: new Date(Date.now() - 300),
    })
  );

  // Cross-user isolation
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("usageQuota").doc("daily").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("usageQuota").doc("daily").update({
      ecoLiteMode: false,
      updatedAt: new Date(Date.now() - 300),
    })
  );
});

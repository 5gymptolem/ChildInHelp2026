import * as admin from "firebase-admin";
import * as functions from "firebase-functions/v1";

admin.initializeApp();

const db = admin.database();

const MAX_ATTEMPTS = 3;
const RETRY_DELAYS_MS = [30_000, 90_000];

type NotificationRequestStatus = "PENDING" | "COMPLETED";

type SosData = {
  latitude?: number;
  longitude?: number;
};

type TokenRecord = {
  token?: string;
};

type RecipientTarget = {
  tokens: string[];
};

type RecipientTargets = Record<string, RecipientTarget>;

type NotificationRequest = {
  sosId: string;
  createdAt: number;
  attemptCount: number;
  maxAttempts: number;
  lastAttemptAt: number;
  status: NotificationRequestStatus;
  targetTokens?: string[];
  targetUsers?: RecipientTargets;
  ackedBy?: Record<string, boolean>;
  openedBy?: Record<string, boolean>;
};

type UserRecord = {
  active?: boolean;
  notificationsEnabled?: boolean;
  certifiedFirstAid?: boolean;
  preferredRadiusKm?: number;
};

type UserLocationRecord = {
  latitude?: number;
  longitude?: number;
  updatedAt?: number;
};

type AppSettingsRecord = {
  defaultRadiusKm?: number;
  maxLocationAgeMinutes?: number;
  notificationRouting?: {
    notifyCertifiedOnly?: boolean;
    notifyNonCertifiedAlso?: boolean;
  };
};

/**
 * Triggered when a new SOS is created.
 */
export const onSosCreated = functions.database
  .ref("/sos/{sosId}")
  .onCreate(async (snapshot, context) => {
    const sosId = context.params.sosId as string;
    const sosData = (snapshot.val() ?? {}) as SosData;

    console.log("New SOS detected:", sosId);

    const targetUsers = await getEligibleTargetUsers(sosData);

    if (countUsersWithTokens(targetUsers) === 0) {
      console.log("No eligible target users/tokens found");
      return null;
    }

    const now = Date.now();
    const requestRef = db.ref("notification_requests").push();

    const request: NotificationRequest = {
      sosId: sosId,
      createdAt: now,
      attemptCount: 1,
      maxAttempts: MAX_ATTEMPTS,
      lastAttemptAt: now,
      status: "PENDING",
      targetUsers: targetUsers,
    };

    await requestRef.set(request);

    const validTargetUsers = await sendPushToTargets(
      targetUsers,
      sosData,
      sosId,
      requestRef.key ?? ""
    );

    const updates: Partial<NotificationRequest> = {
      targetUsers: validTargetUsers,
      targetTokens: null as unknown as string[],
    };

    if (
      countUsersWithTokens(validTargetUsers) === 0 ||
      request.attemptCount >= request.maxAttempts
    ) {
      updates.status = "COMPLETED";
    }

    await requestRef.update(updates);

    console.log(
      "Push sent to users:",
      countUsersWithTokens(validTargetUsers)
    );
    return null;
  });

/**
 * Scheduled retry worker for pending notifications.
 */
export const retryNotifications = functions.pubsub
  .schedule("every 1 minutes")
  .onRun(async () => {
    const now = Date.now();
    const snap = await db.ref("notification_requests").once("value");
    const tasks: Array<Promise<void>> = [];

    snap.forEach((child) => {
      const requestId = child.key;
      const data = child.val() as NotificationRequest | null;

      if (!requestId || !data) {
        return false;
      }

      tasks.push(processRetryRequest(requestId, data, now));
      return false;
    });

    await Promise.all(tasks);
    return null;
  });

/**
 * Processes a single retry request.
 *
 * @param {string} requestId The notification request id.
 * @param {NotificationRequest} data The stored request data.
 * @param {number} now Current timestamp in ms.
 * @return {Promise<void>}
 */
async function processRetryRequest(
  requestId: string,
  data: NotificationRequest,
  now: number
): Promise<void> {
  if (data.status === "COMPLETED") {
    return;
  }

  if (data.attemptCount >= data.maxAttempts) {
    await db
      .ref(`notification_requests/${requestId}/status`)
      .set("COMPLETED");
    console.log(`Request ${requestId} completed due to max attempts.`);
    return;
  }

  const nextAttemptIndex = data.attemptCount;
  const retryDelayIndex = Math.min(
    nextAttemptIndex - 1,
    RETRY_DELAYS_MS.length - 1
  );
  const delayMs = RETRY_DELAYS_MS[retryDelayIndex];

  if (now - data.lastAttemptAt < delayMs) {
    return;
  }

  let targetUsers = sanitizeRecipientTargets(data.targetUsers);

  if (countUsersWithTokens(targetUsers) === 0 && data.targetTokens) {
    targetUsers = await rebuildTargetUsersFromTokens(data.targetTokens);
  }

  const pendingTargetUsers = filterUnackedTargets(
    targetUsers,
    data.ackedBy
  );

  if (countUsersWithTokens(pendingTargetUsers) === 0) {
    await db
      .ref(`notification_requests/${requestId}`)
      .update({
        status: "COMPLETED",
        targetUsers: targetUsers,
        targetTokens: null,
      });

    console.log(
      `Request ${requestId} completed because all users ACKed ` +
      "or no pending tokens remain."
    );
    return;
  }

  console.log("Retrying request:", requestId);

  const sosSnap = await db.ref(`sos/${data.sosId}`).once("value");
  const sosData = (sosSnap.val() ?? {}) as SosData;

  const updatedPendingTargets = await sendPushToTargets(
    pendingTargetUsers,
    sosData,
    data.sosId,
    requestId
  );

  const mergedTargetUsers = mergeTargetUsers(
    targetUsers,
    updatedPendingTargets,
    data.ackedBy
  );

  const nextAttemptCount = data.attemptCount + 1;

  const updates: Partial<NotificationRequest> = {
    attemptCount: nextAttemptCount,
    lastAttemptAt: now,
    targetUsers: mergedTargetUsers,
    targetTokens: null as unknown as string[],
  };

  if (
    countUsersWithTokens(
      filterUnackedTargets(mergedTargetUsers, data.ackedBy)
    ) === 0 ||
    nextAttemptCount >= data.maxAttempts
  ) {
    updates.status = "COMPLETED";
  }

  await db.ref(`notification_requests/${requestId}`).update(updates);
}

/**
 * Reads only eligible user->tokens targets from RTDB,
 * filtered by radius, certified policy, activity, notification
 * preference and fresh location.
 *
 * @param {SosData} sosData SOS location data.
 * @return {Promise<RecipientTargets>}
 */
async function getEligibleTargetUsers(
  sosData: SosData
): Promise<RecipientTargets> {
  const [usersSnap, locationsSnap, tokensSnap, settingsSnap] =
    await Promise.all([
      db.ref("users").once("value"),
      db.ref("user_locations").once("value"),
      db.ref("user_tokens").once("value"),
      db.ref("app_settings").once("value"),
    ]);

  const users = (usersSnap.val() ?? {}) as Record<string, UserRecord>;
  const locations =
    (locationsSnap.val() ?? {}) as Record<string, UserLocationRecord>;
  const settings = (settingsSnap.val() ?? {}) as AppSettingsRecord;

  const defaultRadiusKm = normalizePositiveNumber(
    settings.defaultRadiusKm,
    10
  );

  const maxLocationAgeMinutes = normalizePositiveNumber(
    settings.maxLocationAgeMinutes,
    30
  );

  const notifyCertifiedOnly =
    settings.notificationRouting?.notifyCertifiedOnly === true;

  const notifyNonCertifiedAlso =
    settings.notificationRouting?.notifyNonCertifiedAlso !== false;

  const now = Date.now();
  const result: RecipientTargets = {};

  Object.entries(users).forEach(([uid, user]) => {
    if (!user?.active) {
      return;
    }

    if (user.notificationsEnabled === false) {
      return;
    }

    if (
      notifyCertifiedOnly &&
      user.certifiedFirstAid !== true
    ) {
      return;
    }

    if (
      !notifyCertifiedOnly &&
      !notifyNonCertifiedAlso &&
      user.certifiedFirstAid !== true
    ) {
      return;
    }

    const location = locations[uid];
    if (!location) {
      return;
    }

    const lat = normalizeCoordinate(location.latitude);
    const lon = normalizeCoordinate(location.longitude);

    if (lat == null || lon == null) {
      return;
    }

    const updatedAt = normalizeTimestamp(location.updatedAt);
    const ageMs = now - updatedAt;
    if (ageMs > maxLocationAgeMinutes * 60 * 1000) {
      return;
    }

    const distanceKm = calculateDistanceKm(
      sosData.latitude,
      sosData.longitude,
      lat,
      lon
    );

    const effectiveRadiusKm = normalizePositiveNumber(
      user.preferredRadiusKm,
      defaultRadiusKm
    );

    if (distanceKm > effectiveRadiusKm) {
      return;
    }

    const userTokensNode = tokensSnap.child(uid);
    const tokenSet = new Set<string>();

    userTokensNode.forEach((tokenNode) => {
      const value = tokenNode.val() as string | TokenRecord | null;

      if (typeof value === "string" && value.trim().length > 0) {
        tokenSet.add(value.trim());
        return false;
      }

      if (
        value &&
        typeof value === "object" &&
        typeof value.token === "string" &&
        value.token.trim().length > 0
      ) {
        tokenSet.add(value.token.trim());
      }

      return false;
    });

    if (tokenSet.size > 0) {
      result[uid] = {
        tokens: Array.from(tokenSet),
      };
    }
  });

  return result;
}

/**
 * Rebuilds targetUsers from legacy flat tokens by matching current user_tokens.
 *
 * @param {string[]} targetTokens Flat token list from old requests.
 * @return {Promise<RecipientTargets>}
 */
async function rebuildTargetUsersFromTokens(
  targetTokens: string[]
): Promise<RecipientTargets> {
  const wanted = new Set(targetTokens);
  const allUsers = await getAllTargetUsers();
  const rebuilt: RecipientTargets = {};

  Object.entries(allUsers).forEach(([uid, target]) => {
    const matched = target.tokens.filter((token) => wanted.has(token));

    if (matched.length > 0) {
      rebuilt[uid] = {
        tokens: matched,
      };
    }
  });

  return rebuilt;
}

/**
 * Reads all stored user->tokens targets from RTDB without filters.
 * Used mainly for backward compatibility rebuilds.
 *
 * @return {Promise<RecipientTargets>}
 */
async function getAllTargetUsers(): Promise<RecipientTargets> {
  const tokensSnap = await db.ref("user_tokens").once("value");
  const targetUsers: RecipientTargets = {};

  tokensSnap.forEach((userNode) => {
    const uid = userNode.key;

    if (!uid) {
      return false;
    }

    const tokenSet = new Set<string>();

    userNode.forEach((tokenNode) => {
      const value = tokenNode.val() as string | TokenRecord | null;

      if (typeof value === "string" && value.trim().length > 0) {
        tokenSet.add(value.trim());
        return false;
      }

      if (
        value &&
        typeof value === "object" &&
        typeof value.token === "string" &&
        value.token.trim().length > 0
      ) {
        tokenSet.add(value.token.trim());
      }

      return false;
    });

    if (tokenSet.size > 0) {
      targetUsers[uid] = {
        tokens: Array.from(tokenSet),
      };
    }

    return false;
  });

  return targetUsers;
}

/**
 * Sends push notifications to recipient targets and removes invalid tokens.
 *
 * DATA-ONLY payload so the Android app fully controls display,
 * deduplication and ACK handling.
 *
 * @param {RecipientTargets} targetUsers User->tokens map.
 * @param {SosData} sosData SOS payload data.
 * @param {string} sosId SOS id.
 * @param {string} requestId Notification request id.
 * @return {Promise<RecipientTargets>}
 */
async function sendPushToTargets(
  targetUsers: RecipientTargets,
  sosData: SosData,
  sosId: string,
  requestId: string
): Promise<RecipientTargets> {
  const flattened = flattenTargets(targetUsers);

  if (flattened.tokens.length === 0) {
    return {};
  }

  const message: admin.messaging.MulticastMessage = {
    tokens: flattened.tokens,
    data: {
      type: "sos_alert",
      title: "🚨 Νέο SOS",
      body: "Υπάρχει νέο περιστατικό κοντά σου",
      sosId: sosId,
      requestId: requestId,
      latitude: String(sosData.latitude ?? ""),
      longitude: String(sosData.longitude ?? ""),
    },
    android: {
      priority: "high",
      ttl: 60 * 60 * 1000,
    },
  };

  const response = await admin.messaging().sendEachForMulticast(message);

  console.log("Success:", response.successCount);
  console.log("Failures:", response.failureCount);

  const validByUser: Record<string, Set<string>> = {};
  const cleanupTasks: Array<Promise<void>> = [];

  response.responses.forEach(
    (res: admin.messaging.SendResponse, idx: number) => {
      const token = flattened.tokens[idx];
      const uid = flattened.tokenToUid[token];

      if (!uid) {
        return;
      }

      if (res.success) {
        if (!validByUser[uid]) {
          validByUser[uid] = new Set<string>();
        }

        validByUser[uid].add(token);
        return;
      }

      console.log(
        "Removing bad token:",
        token,
        res.error?.message ?? "unknown error"
      );

      cleanupTasks.push(removeTokenEverywhere(token));
    }
  );

  await Promise.all(cleanupTasks);

  const result: RecipientTargets = {};

  Object.entries(validByUser).forEach(([uid, tokenSet]) => {
    const tokens = Array.from(tokenSet);

    if (tokens.length > 0) {
      result[uid] = {tokens: tokens};
    }
  });

  return result;
}

/**
 * Keeps only recipients who have not ACKed yet.
 *
 * @param {RecipientTargets} targetUsers User->tokens map.
 * @param {Record<string, boolean>|undefined} ackedBy ACK map.
 * @return {RecipientTargets}
 */
function filterUnackedTargets(
  targetUsers: RecipientTargets,
  ackedBy?: Record<string, boolean>
): RecipientTargets {
  const result: RecipientTargets = {};

  Object.entries(targetUsers).forEach(([uid, target]) => {
    if (ackedBy?.[uid] === true) {
      return;
    }

    const tokens = uniqueNonEmptyTokens(target.tokens);

    if (tokens.length > 0) {
      result[uid] = {tokens: tokens};
    }
  });

  return result;
}

/**
 * Merges the full target set with the updated retry result.
 * ACKed users are preserved as-is. Unacked users are replaced
 * with their latest valid token lists.
 *
 * @param {RecipientTargets} previous Existing target users.
 * @param {RecipientTargets} updated Latest valid retry targets.
 * @param {Record<string, boolean>|undefined} ackedBy ACK map.
 * @return {RecipientTargets}
 */
function mergeTargetUsers(
  previous: RecipientTargets,
  updated: RecipientTargets,
  ackedBy?: Record<string, boolean>
): RecipientTargets {
  const merged: RecipientTargets = {};

  Object.entries(previous).forEach(([uid, target]) => {
    if (ackedBy?.[uid] === true) {
      const tokens = uniqueNonEmptyTokens(target.tokens);
      if (tokens.length > 0) {
        merged[uid] = {tokens: tokens};
      }
      return;
    }

    const nextTarget = updated[uid];
    const tokens = nextTarget ?
      uniqueNonEmptyTokens(nextTarget.tokens) :
      [];

    if (tokens.length > 0) {
      merged[uid] = {tokens: tokens};
    }
  });

  return merged;
}

/**
 * Flattens targetUsers into a token list plus token->uid map.
 *
 * @param {RecipientTargets} targetUsers User->tokens map.
 * @return {{tokens: string[], tokenToUid: Record<string, string>}}
 */
function flattenTargets(
  targetUsers: RecipientTargets
): {
  tokens: string[];
  tokenToUid: Record<string, string>;
} {
  const tokens: string[] = [];
  const tokenToUid: Record<string, string> = {};

  Object.entries(targetUsers).forEach(([uid, target]) => {
    const uniqueTokens = uniqueNonEmptyTokens(target.tokens);

    uniqueTokens.forEach((token) => {
      if (tokenToUid[token]) {
        return;
      }

      tokens.push(token);
      tokenToUid[token] = uid;
    });
  });

  return {tokens, tokenToUid};
}

/**
 * Counts recipients that still have at least one token.
 *
 * @param {RecipientTargets|undefined} targetUsers User->tokens map.
 * @return {number}
 */
function countUsersWithTokens(targetUsers?: RecipientTargets): number {
  if (!targetUsers) {
    return 0;
  }

  let count = 0;

  Object.values(targetUsers).forEach((target) => {
    if (uniqueNonEmptyTokens(target.tokens).length > 0) {
      count += 1;
    }
  });

  return count;
}

/**
 * Normalizes and cleans a RecipientTargets object.
 *
 * @param {RecipientTargets|undefined} targetUsers Raw targets.
 * @return {RecipientTargets}
 */
function sanitizeRecipientTargets(
  targetUsers?: RecipientTargets
): RecipientTargets {
  const result: RecipientTargets = {};

  if (!targetUsers) {
    return result;
  }

  Object.entries(targetUsers).forEach(([uid, target]) => {
    const tokens = uniqueNonEmptyTokens(target?.tokens ?? []);

    if (tokens.length > 0) {
      result[uid] = {tokens: tokens};
    }
  });

  return result;
}

/**
 * Deduplicates and trims token arrays.
 *
 * @param {string[]} tokens Tokens list.
 * @return {string[]}
 */
function uniqueNonEmptyTokens(tokens: string[]): string[] {
  const set = new Set<string>();

  tokens.forEach((token) => {
    const normalized = token.trim();
    if (normalized.length > 0) {
      set.add(normalized);
    }
  });

  return Array.from(set);
}

/**
 * Haversine distance in kilometers.
 *
 * @param {number|undefined} lat1 Point 1 latitude.
 * @param {number|undefined} lon1 Point 1 longitude.
 * @param {number|undefined} lat2 Point 2 latitude.
 * @param {number|undefined} lon2 Point 2 longitude.
 * @return {number}
 */
function calculateDistanceKm(
  lat1?: number,
  lon1?: number,
  lat2?: number,
  lon2?: number
): number {
  if (
    lat1 == null ||
    lon1 == null ||
    lat2 == null ||
    lon2 == null
  ) {
    return Number.POSITIVE_INFINITY;
  }

  const earthRadiusKm = 6371;

  const dLat = degreesToRadians(lat2 - lat1);
  const dLon = degreesToRadians(lon2 - lon1);

  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(degreesToRadians(lat1)) *
    Math.cos(degreesToRadians(lat2)) *
    Math.sin(dLon / 2) ** 2;

  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

  return earthRadiusKm * c;
}

/**
 * Converts degrees to radians.
 *
 * @param {number} degrees Angle in degrees.
 * @return {number}
 */
function degreesToRadians(degrees: number): number {
  return degrees * Math.PI / 180;
}

/**
 * Normalizes a positive numeric setting.
 *
 * @param {number|undefined} value Raw value.
 * @param {number} fallback Fallback value.
 * @return {number}
 */
function normalizePositiveNumber(
  value: number | undefined,
  fallback: number
): number {
  if (
    typeof value !== "number" ||
    !Number.isFinite(value) ||
    value <= 0
  ) {
    return fallback;
  }

  return value;
}

/**
 * Normalizes a timestamp.
 *
 * @param {number|undefined} value Raw timestamp.
 * @return {number}
 */
function normalizeTimestamp(value?: number): number {
  if (
    typeof value !== "number" ||
    !Number.isFinite(value) ||
    value <= 0
  ) {
    return 0;
  }

  return value;
}

/**
 * Normalizes latitude/longitude values.
 *
 * @param {number|undefined} value Raw coordinate.
 * @return {number|null}
 */
function normalizeCoordinate(value?: number): number | null {
  if (
    typeof value !== "number" ||
    !Number.isFinite(value)
  ) {
    return null;
  }

  return value;
}

/**
 * Removes a token from every user_tokens location where it exists.
 *
 * @param {string} token The token to remove.
 * @return {Promise<void>}
 */
async function removeTokenEverywhere(token: string): Promise<void> {
  const snap = await db.ref("user_tokens").once("value");
  const removals: Array<Promise<void>> = [];

  snap.forEach((userNode) => {
    userNode.forEach((tokenNode) => {
      const value = tokenNode.val() as string | TokenRecord | null;

      if (typeof value === "string" && value === token) {
        removals.push(tokenNode.ref.remove());
        return false;
      }

      if (
        value &&
        typeof value === "object" &&
        typeof value.token === "string" &&
        value.token === token
      ) {
        removals.push(tokenNode.ref.remove());
      }

      return false;
    });

    return false;
  });

  await Promise.all(removals);
}

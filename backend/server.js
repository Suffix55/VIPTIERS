
const express = require("express");
const crypto = require("crypto");
const { Pool } = require("pg");

const app = express();
app.use(express.json({ limit: "100kb" }));

const PORT = process.env.PORT || 3000;
const API_SECRET = process.env.API_SECRET;
const DATABASE_URL = process.env.DATABASE_URL;
const WEBSITE_ORIGIN = "https://suffix55.github.io";

const CODE_LIFETIME = 10 * 60 * 1000;
const LINK_CODE_LIFETIME = 5 * 60 * 1000;
const MAX_QUEUE_SIZE = 10;

if (!DATABASE_URL || !API_SECRET) {
    console.error("DATABASE_URL or API_SECRET is missing!");
    process.exit(1);
}

const pool = new Pool({
    connectionString: DATABASE_URL,
    ssl: { rejectUnauthorized: false }
});

const GAMEMODES = [
    "Crystal", "UHC", "Pot", "NethOP", "DiaSMP",
    "Sword", "Axe", "Mace", "Spear Mace", "Cart"
];

const TIERS = [
    "HT1", "LT1", "HT2", "LT2", "HT3",
    "LT3", "HT4", "LT4", "HT5", "LT5"
];

const REGIONS = ["EU", "NA", "AS", "AU"];

const HT_PATHS = {
    LT3: "HT3",
    HT3: "LT2",
    LT2: "HT2",
    HT2: "LT1",
    LT1: "HT1"
};

const TESTER_ROLES = {
    ht: ["headTester", "seniorTester"],
    normal: [
        "headTester",
        "seniorTester",
        "tester",
        "trialTester"
    ]
};

// ==================================================
// DATABASE
// ==================================================

async function initializeDatabase() {
    await pool.query(`
        CREATE TABLE IF NOT EXISTS players (
            uuid TEXT PRIMARY KEY,
            username TEXT NOT NULL,
            verified_at BIGINT NOT NULL,
            gamemodes JSONB NOT NULL DEFAULT '{}'::jsonb
        )
    `);

    await pool.query(`
        CREATE UNIQUE INDEX IF NOT EXISTS players_username_lower_idx
        ON players (LOWER(username))
    `);

    await pool.query(`
        CREATE TABLE IF NOT EXISTS discord_links (
            uuid TEXT PRIMARY KEY REFERENCES players(uuid) ON DELETE CASCADE,
            discord_id TEXT UNIQUE NOT NULL,
            linked_at BIGINT NOT NULL
        )
    `);

    await pool.query(`
        CREATE TABLE IF NOT EXISTS discord_verification_codes (
            code TEXT PRIMARY KEY,
            discord_id TEXT NOT NULL UNIQUE,
            expires_at BIGINT NOT NULL
        )
    `);

    await pool.query(`
        CREATE INDEX IF NOT EXISTS discord_verification_codes_expiry_idx
        ON discord_verification_codes(expires_at)
    `);

    await pool.query(`
        CREATE TABLE IF NOT EXISTS link_codes (
            code TEXT PRIMARY KEY,
            uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
            expires_at BIGINT NOT NULL
        )
    `);

    await pool.query(`
        CREATE TABLE IF NOT EXISTS queue_entries (
            id BIGSERIAL PRIMARY KEY,
            uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
            queue_type TEXT NOT NULL CHECK (queue_type IN ('normal', 'ht')),
            gamemode TEXT NOT NULL,
            current_tier TEXT,
            target_tier TEXT,
            region TEXT,
            joined_at BIGINT NOT NULL,
            status TEXT NOT NULL DEFAULT 'waiting'
                CHECK (status IN ('waiting', 'testing', 'completed')),
            tester_discord_id TEXT,
            ticket_channel_id TEXT
        )
    `);

    // Safe migration for existing databases.
    await pool.query(`
        ALTER TABLE queue_entries
        ADD COLUMN IF NOT EXISTS region TEXT
    `);

    await pool.query(`
        CREATE UNIQUE INDEX IF NOT EXISTS queue_one_active_per_player
        ON queue_entries(uuid)
        WHERE status IN ('waiting', 'testing')
    `);

    await pool.query(`
        CREATE INDEX IF NOT EXISTS queue_waiting_lookup
        ON queue_entries(queue_type, gamemode, joined_at, id)
        WHERE status = 'waiting'
    `);

    console.log("VIPTIERS PostgreSQL database connected!");
}

// ==================================================
// CORS
// ==================================================

app.use((req, res, next) => {
    const origin = req.headers.origin;

    if (origin === WEBSITE_ORIGIN) {
        res.setHeader("Access-Control-Allow-Origin", origin);
        res.setHeader("Vary", "Origin");

        res.setHeader(
            "Access-Control-Allow-Methods",
            "GET, POST, DELETE, OPTIONS"
        );

        res.setHeader(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization"
        );
    }

    if (req.method === "OPTIONS") {
        return res.sendStatus(204);
    }

    next();
});

// ==================================================
// AUTHENTICATION
// ==================================================

function authenticate(req, res, next) {
    const supplied = req.headers.authorization || "";

    if (!API_SECRET || supplied !== `Bearer ${API_SECRET}`) {
        return res.status(401).json({
            success: false,
            error: "Unauthorized"
        });
    }

    next();
}

// ==================================================
// HELPERS
// ==================================================

function validUsername(username) {
    return /^[a-zA-Z0-9_]{3,16}$/.test(username);
}

function validUUID(uuid) {
    return /^[a-f0-9]{32}$/.test(uuid);
}

function normalizeUUID(uuid) {
    return String(uuid || "")
        .replace(/-/g, "")
        .toLowerCase();
}

function validDiscordId(id) {
    return /^\d{17,20}$/.test(String(id || ""));
}

function validRegion(region) {
    return REGIONS.includes(String(region || "").toUpperCase());
}

function normalizeRegion(region) {
    return String(region || "").trim().toUpperCase();
}

function createGamemodes() {
    const result = {};

    for (const mode of GAMEMODES) {
        result[mode] = {
            tested: false,
            tier: null,
            testedAt: null,
            history: []
        };
    }

    return result;
}

async function findPlayerByUsername(username) {
    const result = await pool.query(
        `SELECT * FROM players
         WHERE LOWER(username) = LOWER($1)`,
        [username]
    );

    return result.rows[0] || null;
}

async function findPlayerByDiscordId(discordId) {
    const result = await pool.query(
        `SELECT p.*
         FROM players p
         INNER JOIN discord_links d ON d.uuid = p.uuid
         WHERE d.discord_id = $1`,
        [String(discordId)]
    );

    return result.rows[0] || null;
}

async function resolveQueuePlayer(body) {
    if (body.uuid) {
        const uuid = normalizeUUID(body.uuid);

        if (!validUUID(uuid)) {
            throw new Error("Invalid Minecraft UUID");
        }

        const result = await pool.query(
            "SELECT * FROM players WHERE uuid = $1",
            [uuid]
        );

        return result.rows[0] || null;
    }

    if (body.discordId) {
        if (!validDiscordId(body.discordId)) {
            throw new Error("Invalid Discord ID");
        }

        return await findPlayerByDiscordId(body.discordId);
    }

    throw new Error(
        "A Minecraft UUID or linked Discord ID is required"
    );
}

function validQueueType(type) {
    return type === "normal" || type === "ht";
}

function validGamemode(gamemode) {
    return GAMEMODES.includes(gamemode);
}

function validHTPath(currentTier, targetTier) {
    return HT_PATHS[currentTier] === targetTier;
}

async function getLeaderboard(gamemode = null) {
    const result = await pool.query(
        "SELECT uuid, username, gamemodes FROM players"
    );

    const players = [];

    for (const player of result.rows) {
        const modes = player.gamemodes || {};

        for (const mode of GAMEMODES) {
            if (gamemode && mode !== gamemode) continue;

            const data = modes[mode];

            if (!data || !data.tested || !data.tier) continue;

            players.push({
                uuid: player.uuid,
                username: player.username,
                tier: data.tier,
                gamemode: mode,
                testedAt: data.testedAt
            });
        }
    }

    players.sort((a, b) => {
        const difference =
            TIERS.indexOf(a.tier) - TIERS.indexOf(b.tier);

        if (difference !== 0) return difference;

        return a.username.localeCompare(b.username);
    });

    return players.map((player, index) => ({
        position: index + 1,
        ...player
    }));
}

// ==================================================
// HEALTH CHECK
// ==================================================

app.get("/", (req, res) => {
    res.json({
        name: "VIPTIERS",
        status: "online",
        database: "PostgreSQL"
    });
});

// ==================================================
// DISCORD + MINECRAFT VERIFICATION
// ==================================================

app.post(
    "/api/verification/discord-code",
    authenticate,
    async (req, res) => {
        const discordId = String(req.body.discordId || "").trim();

        if (!validDiscordId(discordId)) {
            return res.status(400).json({
                success: false,
                error: "Invalid Discord ID"
            });
        }

        try {
            await pool.query(
                "DELETE FROM discord_verification_codes WHERE expires_at <= $1",
                [Date.now()]
            );

            const existingLink = await pool.query(
                "SELECT uuid FROM discord_links WHERE discord_id = $1",
                [discordId]
            );

            if (existingLink.rows.length) {
                return res.status(409).json({
                    success: false,
                    error: "This Discord account is already linked to a Minecraft account"
                });
            }

            for (let attempt = 0; attempt < 20; attempt++) {
                const code = crypto.randomInt(0, 1000000)
                    .toString()
                    .padStart(6, "0");

                try {
                    const result = await pool.query(
                        `INSERT INTO discord_verification_codes
                            (code, discord_id, expires_at)
                         VALUES ($1, $2, $3)
                         ON CONFLICT (discord_id)
                         DO UPDATE SET
                            code = EXCLUDED.code,
                            expires_at = EXCLUDED.expires_at
                         RETURNING code`,
                        [
                            code,
                            discordId,
                            Date.now() + CODE_LIFETIME
                        ]
                    );

                    return res.json({
                        success: true,
                        code: result.rows[0].code,
                        expiresIn: CODE_LIFETIME / 1000,
                        message: "Verification code generated successfully"
                    });
                } catch (error) {
                    if (error.code === "23505") continue;
                    throw error;
                }
            }

            return res.status(500).json({
                success: false,
                error: "Could not generate a unique verification code"
            });
        } catch (error) {
            console.error("Discord verification code error:", error);

            return res.status(500).json({
                success: false,
                error: "Could not generate verification code"
            });
        }
    }
);

app.post(
    "/api/verification/complete",
    authenticate,
    async (req, res) => {
        const uuid = normalizeUUID(req.body.uuid);
        const username = String(req.body.username || "").trim();
        const code = String(req.body.code || "").trim();

        if (
            !validUUID(uuid) ||
            !validUsername(username) ||
            !/^\d{6}$/.test(code)
        ) {
            return res.status(400).json({
                success: false,
                error: "Invalid verification details"
            });
        }

        const client = await pool.connect();

        try {
            await client.query("BEGIN");

            const codeResult = await client.query(
                `SELECT discord_id
                 FROM discord_verification_codes
                 WHERE code = $1
                   AND expires_at > $2
                 FOR UPDATE`,
                [code, Date.now()]
            );

            if (!codeResult.rows.length) {
                await client.query("ROLLBACK");

                return res.status(400).json({
                    success: false,
                    error: "Incorrect or expired verification code"
                });
            }

            const discordId = codeResult.rows[0].discord_id;

            const existingPlayerResult = await client.query(
                `SELECT *
                 FROM players
                 WHERE uuid = $1
                 FOR UPDATE`,
                [uuid]
            );

            const existingPlayer = existingPlayerResult.rows[0] || null;

            const usernameOwnerResult = await client.query(
                `SELECT uuid
                 FROM players
                 WHERE LOWER(username) = LOWER($1)
                 FOR UPDATE`,
                [username]
            );

            const usernameOwner = usernameOwnerResult.rows[0] || null;

            if (usernameOwner && usernameOwner.uuid !== uuid) {
                await client.query("ROLLBACK");

                return res.status(409).json({
                    success: false,
                    error: "This Minecraft username is already registered to another account"
                });
            }

            const existingMinecraftLink = await client.query(
                "SELECT discord_id FROM discord_links WHERE uuid = $1",
                [uuid]
            );

            if (existingMinecraftLink.rows.length) {
                const linkedDiscordId =
                    existingMinecraftLink.rows[0].discord_id;

                await client.query("ROLLBACK");

                if (linkedDiscordId === discordId) {
                    return res.status(409).json({
                        success: false,
                        error: "This Minecraft account is already linked to your Discord account"
                    });
                }

                return res.status(409).json({
                    success: false,
                    error: "This Minecraft account is already linked to another Discord account"
                });
            }

            const existingDiscordLink = await client.query(
                "SELECT uuid FROM discord_links WHERE discord_id = $1",
                [discordId]
            );

            if (existingDiscordLink.rows.length) {
                await client.query("ROLLBACK");

                return res.status(409).json({
                    success: false,
                    error: "This Discord account is already linked to another Minecraft account"
                });
            }

            const verifiedAt = existingPlayer
                ? existingPlayer.verified_at
                : Date.now();

            const gamemodes = existingPlayer
                ? existingPlayer.gamemodes
                : createGamemodes();

            await client.query(
                `INSERT INTO players
                    (uuid, username, verified_at, gamemodes)
                 VALUES ($1, $2, $3, $4)
                 ON CONFLICT (uuid)
                 DO UPDATE SET username = EXCLUDED.username`,
                [
                    uuid,
                    username,
                    verifiedAt,
                    JSON.stringify(gamemodes)
                ]
            );

            await client.query(
                `INSERT INTO discord_links
                    (uuid, discord_id, linked_at)
                 VALUES ($1, $2, $3)`,
                [uuid, discordId, Date.now()]
            );

            await client.query(
                "DELETE FROM discord_verification_codes WHERE discord_id = $1",
                [discordId]
            );

            await client.query("COMMIT");

            return res.json({
                success: true,
                verified: true,
                discordLinked: true,
                uuid,
                username,
                discordId,
                message: "Minecraft account verified and Discord account linked successfully!"
            });
        } catch (error) {
            await client.query("ROLLBACK");

            if (error.code === "23505") {
                return res.status(409).json({
                    success: false,
                    error: "This Minecraft or Discord account is already linked"
                });
            }

            console.error("Verification completion error:", error);

            return res.status(500).json({
                success: false,
                error: "Could not complete verification"
            });
        } finally {
            client.release();
        }
    }
);

// ==================================================
// VERIFICATION STATUS
// ==================================================

app.get(
    "/api/verification/status/:uuid",
    authenticate,
    async (req, res) => {
        const uuid = normalizeUUID(req.params.uuid);

        if (!validUUID(uuid)) {
            return res.status(400).json({
                verified: false,
                error: "Invalid UUID"
            });
        }

        try {
            const result = await pool.query(
                "SELECT * FROM players WHERE uuid = $1",
                [uuid]
            );

            const player = result.rows[0];

            if (!player) {
                return res.status(404).json({
                    verified: false,
                    error: "Player not verified"
                });
            }

            res.json({
                verified: true,
                uuid,
                username: player.username,
                verifiedAt: Number(player.verified_at)
            });
        } catch (error) {
            console.error("Status error:", error);

            res.status(500).json({
                verified: false,
                error: "Database error"
            });
        }
    }
);

// ==================================================
// VERIFIED PLAYERS
// ==================================================

app.get(
    "/api/verification/players",
    authenticate,
    async (req, res) => {
        try {
            const result = await pool.query(
                "SELECT * FROM players ORDER BY username"
            );

            const players = result.rows.map(player => ({
                uuid: player.uuid,
                username: player.username,
                verifiedAt: Number(player.verified_at),
                gamemodes: player.gamemodes
            }));

            res.json({
                success: true,
                count: players.length,
                players
            });
        } catch (error) {
            console.error("Players error:", error);

            res.status(500).json({
                success: false,
                error: "Database error"
            });
        }
    }
);

// ==================================================
// PUBLIC PLAYER PROFILE
// ==================================================

app.get("/api/verification/player/:username", async (req, res) => {
    res.setHeader("Cache-Control", "no-store");

    try {
        const player = await findPlayerByUsername(req.params.username);

        if (!player) {
            return res.status(404).json({
                success: false,
                error: "Verified player not found"
            });
        }

        res.json({
            success: true,
            player: {
                uuid: player.uuid,
                username: player.username,
                verifiedAt: Number(player.verified_at),
                gamemodes: player.gamemodes
            }
        });
    } catch (error) {
        console.error("Profile error:", error);

        res.status(500).json({
            success: false,
            error: "Database error"
        });
    }
});

// ==================================================
// UNVERIFY PLAYER
// ==================================================

app.delete(
    "/api/verification/player/:username",
    authenticate,
    async (req, res) => {
        try {
            const player = await findPlayerByUsername(req.params.username);

            if (!player) {
                return res.status(404).json({
                    success: false,
                    error: "Verified player not found"
                });
            }

            await pool.query(
                "DELETE FROM players WHERE uuid = $1",
                [player.uuid]
            );

            res.json({
                success: true,
                message: `${player.username} has been unverified`
            });
        } catch (error) {
            console.error("Unverify error:", error);

            res.status(500).json({
                success: false,
                error: "Database error"
            });
        }
    }
);

// ==================================================
// LEGACY DISCORD ACCOUNT LINKING
// ==================================================

app.post(
    "/api/link/start",
    authenticate,
    async (req, res) => {
        const uuid = normalizeUUID(req.body.uuid);

        if (!validUUID(uuid)) {
            return res.status(400).json({
                success: false,
                error: "Invalid Minecraft UUID"
            });
        }

        try {
            const playerResult = await pool.query(
                "SELECT uuid FROM players WHERE uuid = $1",
                [uuid]
            );

            if (!playerResult.rows.length) {
                return res.status(403).json({
                    success: false,
                    error: "Verify your Minecraft account first"
                });
            }

            const existingLink = await pool.query(
                "SELECT discord_id FROM discord_links WHERE uuid = $1",
                [uuid]
            );

            if (existingLink.rows.length) {
                return res.status(409).json({
                    success: false,
                    error: "Your Minecraft account is already linked"
                });
            }

            await pool.query(
                "DELETE FROM link_codes WHERE uuid = $1 OR expires_at <= $2",
                [uuid, Date.now()]
            );

            const code = crypto.randomInt(0, 100000000)
                .toString()
                .padStart(8, "0");

            await pool.query(
                `INSERT INTO link_codes (code, uuid, expires_at)
                 VALUES ($1, $2, $3)`,
                [code, uuid, Date.now() + LINK_CODE_LIFETIME]
            );

            res.json({
                success: true,
                code,
                expiresIn: LINK_CODE_LIFETIME / 1000,
                message: "Enter this code using the Discord Link Account button"
            });
        } catch (error) {
            console.error("Link start error:", error);

            res.status(500).json({
                success: false,
                error: "Could not generate linking code"
            });
        }
    }
);

app.post(
    "/api/link/complete",
    authenticate,
    async (req, res) => {
        const code = String(req.body.code || "").trim();
        const discordId = String(req.body.discordId || "").trim();

        if (
            !/^\d{8}$/.test(code) ||
            !validDiscordId(discordId)
        ) {
            return res.status(400).json({
                success: false,
                error: "Invalid linking code or Discord ID"
            });
        }

        const client = await pool.connect();

        try {
            await client.query("BEGIN");

            const codeResult = await client.query(
                `SELECT * FROM link_codes
                 WHERE code = $1 AND expires_at > $2
                 FOR UPDATE`,
                [code, Date.now()]
            );

            if (!codeResult.rows.length) {
                await client.query("ROLLBACK");

                return res.status(400).json({
                    success: false,
                    error: "Invalid or expired linking code"
                });
            }

            const uuid = codeResult.rows[0].uuid;

            const existingMinecraftLink = await client.query(
                "SELECT discord_id FROM discord_links WHERE uuid = $1",
                [uuid]
            );

            const existingDiscordLink = await client.query(
                "SELECT uuid FROM discord_links WHERE discord_id = $1",
                [discordId]
            );

            if (existingMinecraftLink.rows.length) {
                await client.query("ROLLBACK");

                return res.status(409).json({
                    success: false,
                    error: "This Minecraft account is already linked"
                });
            }

            if (existingDiscordLink.rows.length) {
                await client.query("ROLLBACK");

                return res.status(409).json({
                    success: false,
                    error: "This Discord account is already linked"
                });
            }

            await client.query(
                `INSERT INTO discord_links (uuid, discord_id, linked_at)
                 VALUES ($1, $2, $3)`,
                [uuid, discordId, Date.now()]
            );

            await client.query(
                "DELETE FROM link_codes WHERE code = $1",
                [code]
            );

            const playerResult = await client.query(
                "SELECT username FROM players WHERE uuid = $1",
                [uuid]
            );

            await client.query("COMMIT");

            res.json({
                success: true,
                message: "Discord account linked successfully",
                username: playerResult.rows[0]?.username || null
            });
        } catch (error) {
            await client.query("ROLLBACK");

            console.error("Link complete error:", error);

            res.status(500).json({
                success: false,
                error: "Could not link accounts"
            });
        } finally {
            client.release();
        }
    }
);

// ==================================================
// DISCORD LINK STATUS
// ==================================================

app.get(
    "/api/link/status",
    authenticate,
    async (req, res) => {
        const discordId = String(req.query.discordId || "");

        if (!validDiscordId(discordId)) {
            return res.status(400).json({
                success: false,
                error: "Invalid Discord ID"
            });
        }

        try {
            const result = await pool.query(
                `SELECT p.username, p.uuid, d.linked_at
                 FROM discord_links d
                 INNER JOIN players p ON p.uuid = d.uuid
                 WHERE d.discord_id = $1`,
                [discordId]
            );

            if (!result.rows.length) {
                return res.json({
                    success: true,
                    linked: false
                });
            }

            res.json({
                success: true,
                linked: true,
                player: {
                    username: result.rows[0].username,
                    uuid: result.rows[0].uuid,
                    linkedAt: Number(result.rows[0].linked_at)
                }
            });
        } catch (error) {
            console.error("Link status error:", error);

            res.status(500).json({
                success: false,
                error: "Database error"
            });
        }
    }
);

// ==================================================
// QUEUE JOIN
// ==================================================

app.post(
    "/api/queue/join",
    authenticate,
    async (req, res) => {
        const queueType = String(req.body.queueType || "").toLowerCase();
        const gamemode = String(req.body.gamemode || "").trim();

        const currentTier = req.body.currentTier
            ? String(req.body.currentTier).toUpperCase().trim()
            : null;

        const targetTier = req.body.targetTier
            ? String(req.body.targetTier).toUpperCase().trim()
            : null;

        const region = normalizeRegion(req.body.region);

        if (!validQueueType(queueType)) {
            return res.status(400).json({
                success: false,
                error: "Queue type must be normal or ht"
            });
        }

        if (!validGamemode(gamemode)) {
            return res.status(400).json({
                success: false,
                error: "Invalid gamemode",
                gamemodes: GAMEMODES
            });
        }

        if (!validRegion(region)) {
            return res.status(400).json({
                success: false,
                error: "Select a valid region",
                regions: REGIONS
            });
        }

        if (queueType === "ht") {
            if (
                !currentTier ||
                !targetTier ||
                !validHTPath(currentTier, targetTier)
            ) {
                return res.status(400).json({
                    success: false,
                    error: "Invalid HT promotion path",
                    allowedPaths: HT_PATHS
                });
            }
        }

        if (
            (currentTier && !TIERS.includes(currentTier)) ||
            (targetTier && !TIERS.includes(targetTier))
        ) {
            return res.status(400).json({
                success: false,
                error: "Invalid tier"
            });
        }

        const client = await pool.connect();

        try {
            await client.query("BEGIN");

            const player = await resolveQueuePlayer(req.body);

            if (!player) {
                await client.query("ROLLBACK");

                return res.status(404).json({
                    success: false,
                    error: "Verified Minecraft account not found"
                });
            }

            const linkResult = await client.query(
                "SELECT discord_id FROM discord_links WHERE uuid = $1",
                [player.uuid]
            );

            if (!linkResult.rows.length) {
                await client.query("ROLLBACK");

                return res.status(403).json({
                    success: false,
                    error: "Link your Minecraft account to Discord before joining a queue"
                });
            }

            await client.query(
                "SELECT pg_advisory_xact_lock(hashtext($1))",
                [`queue:${queueType}:${gamemode}`]
            );

            await client.query(
                "SELECT pg_advisory_xact_lock(hashtext($1))",
                [`player:${player.uuid}`]
            );

            if (queueType === "ht") {
                const modes = player.gamemodes || {};
                const existingTier = modes[gamemode]?.tier || null;

                if (existingTier !== currentTier) {
                    await client.query("ROLLBACK");

                    return res.status(400).json({
                        success: false,
                        error: "Your current website tier does not match the HT test path",
                        currentTier: existingTier
                    });
                }
            }

            const active = await client.query(
                `SELECT id, queue_type, gamemode, status
                 FROM queue_entries
                 WHERE uuid = $1
                   AND status IN ('waiting', 'testing')
                 LIMIT 1`,
                [player.uuid]
            );

            if (active.rows.length) {
                await client.query("ROLLBACK");

                return res.status(409).json({
                    success: false,
                    error: "You are already in an active queue",
                    queue: active.rows[0]
                });
            }

            const countResult = await client.query(
                `SELECT COUNT(*)::int AS count
                 FROM queue_entries
                 WHERE queue_type = $1
                   AND gamemode = $2
                   AND status = 'waiting'`,
                [queueType, gamemode]
            );

            const count = countResult.rows[0].count;

            if (count >= MAX_QUEUE_SIZE) {
                await client.query("ROLLBACK");

                return res.status(409).json({
                    success: false,
                    error: "This queue is full",
                    maximum: MAX_QUEUE_SIZE
                });
            }

            const insertResult = await client.query(
                `INSERT INTO queue_entries
                    (uuid, queue_type, gamemode, current_tier,
                     target_tier, region, joined_at, status)
                 VALUES ($1, $2, $3, $4, $5, $6, $7, 'waiting')
                 RETURNING id`,
                [
                    player.uuid,
                    queueType,
                    gamemode,
                    currentTier,
                    targetTier,
                    region,
                    Date.now()
                ]
            );

            await client.query("COMMIT");

            res.json({
                success: true,
                message: `Joined ${gamemode} ${queueType} queue`,
                queueEntryId: insertResult.rows[0].id,
                username: player.username,
                queueType,
                gamemode,
                region,
                position: count + 1,
                maximum: MAX_QUEUE_SIZE
            });
        } catch (error) {
            await client.query("ROLLBACK");

            if (error.code === "23505") {
                return res.status(409).json({
                    success: false,
                    error: "You already have an active queue entry"
                });
            }

            console.error("Queue join error:", error);

            res.status(500).json({
                success: false,
                error: "Could not join queue"
            });
        } finally {
            client.release();
        }
    }
);

// ==================================================
// QUEUE VIEW
// ==================================================

app.get(
    "/api/queue/:queueType/:gamemode",
    async (req, res) => {
        const queueType = String(req.params.queueType).toLowerCase();
        const gamemode = String(req.params.gamemode);

        res.setHeader("Cache-Control", "no-store");

        if (!validQueueType(queueType) || !validGamemode(gamemode)) {
            return res.status(400).json({
                success: false,
                error: "Invalid queue type or gamemode"
            });
        }

        try {
            const waitingResult = await pool.query(
                `SELECT
                    q.id,
                    p.username,
                    d.discord_id,
                    q.current_tier,
                    q.target_tier,
                    q.region,
                    q.joined_at
                 FROM queue_entries q
                 INNER JOIN players p ON p.uuid = q.uuid
                 INNER JOIN discord_links d ON d.uuid = q.uuid
                 WHERE q.queue_type = $1
                   AND q.gamemode = $2
                   AND q.status = 'waiting'
                 ORDER BY q.joined_at ASC, q.id ASC`,
                [queueType, gamemode]
            );

            const testersResult = await pool.query(
                `SELECT DISTINCT tester_discord_id
                 FROM queue_entries
                 WHERE queue_type = $1
                   AND gamemode = $2
                   AND status = 'testing'
                   AND tester_discord_id IS NOT NULL
                 ORDER BY tester_discord_id`,
                [queueType, gamemode]
            );

            const players = waitingResult.rows.map((entry, index) => ({
                id: entry.id,
                position: index + 1,
                username: entry.username,
                discordId: entry.discord_id,
                currentTier: entry.current_tier,
                targetTier: entry.target_tier,
                region: entry.region,
                joinedAt: Number(entry.joined_at),
                status: "waiting"
            }));

            const activeTesters = testersResult.rows.map(entry => ({
                discordId: entry.tester_discord_id
            }));

            const waiting = players.length;

            res.json({
                success: true,
                queueType,
                gamemode,
                name: `${gamemode.toUpperCase()} ${queueType.toUpperCase()} QUEUE`,
                waiting,
                maximum: MAX_QUEUE_SIZE,
                full: waiting >= MAX_QUEUE_SIZE,
                regions: REGIONS,
                players,
                activeTesters
            });
        } catch (error) {
            console.error("Queue view error:", error);

            res.status(500).json({
                success: false,
                error: "Could not load queue"
            });
        }
    }
);

// ==================================================
// QUEUE LEAVE
// ==================================================

app.post(
    "/api/queue/leave",
    authenticate,
    async (req, res) => {
        try {
            const player = await resolveQueuePlayer(req.body);

            if (!player) {
                return res.status(404).json({
                    success: false,
                    error: "Player account not found"
                });
            }

            const result = await pool.query(
                `UPDATE queue_entries
                 SET status = 'completed'
                 WHERE uuid = $1
                   AND status = 'waiting'
                 RETURNING id, queue_type, gamemode, region`,
                [player.uuid]
            );

            if (!result.rows.length) {
                return res.status(404).json({
                    success: false,
                    error: "You are not waiting in a queue"
                });
            }

            res.json({
                success: true,
                message: "You left the queue",
                queue: result.rows[0]
            });
        } catch (error) {
            console.error("Queue leave error:", error);

            res.status(500).json({
                success: false,
                error: "Could not leave queue"
            });
        }
    }
);

// ==================================================
// TESTER: SELECT NEXT PLAYER
// ==================================================

app.post(
    "/api/queue/next",
    authenticate,
    async (req, res) => {
        const queueType = String(req.body.queueType || "").toLowerCase();
        const gamemode = String(req.body.gamemode || "").trim();
        const testerDiscordId = String(
            req.body.testerDiscordId || ""
        ).trim();

        const testerRole = String(
            req.body.testerRole || ""
        ).toLowerCase();

        if (
            !validQueueType(queueType) ||
            !validGamemode(gamemode) ||
            !validDiscordId(testerDiscordId)
        ) {
            return res.status(400).json({
                success: false,
                error: "Invalid queue or tester information"
            });
        }

        if (!TESTER_ROLES[queueType].includes(testerRole)) {
            return res.status(403).json({
                success: false,
                error: "You are not allowed to handle this queue"
            });
        }

        const client = await pool.connect();

        try {
            await client.query("BEGIN");

            await client.query(
                "SELECT pg_advisory_xact_lock(hashtext($1))",
                [`queue:${queueType}:${gamemode}`]
            );

            const nextResult = await client.query(
                `SELECT
                    q.id,
                    q.uuid,
                    q.current_tier,
                    q.target_tier,
                    q.region,
                    q.joined_at,
                    p.username,
                    d.discord_id
                 FROM queue_entries q
                 INNER JOIN players p ON p.uuid = q.uuid
                 INNER JOIN discord_links d ON d.uuid = q.uuid
                 WHERE q.queue_type = $1
                   AND q.gamemode = $2
                   AND q.status = 'waiting'
                 ORDER BY q.joined_at ASC, q.id ASC
                 LIMIT 1
                 FOR UPDATE OF q SKIP LOCKED`,
                [queueType, gamemode]
            );

            if (!nextResult.rows.length) {
                await client.query("ROLLBACK");

                return res.status(404).json({
                    success: false,
                    error: "There are no players waiting in this queue"
                });
            }

            const player = nextResult.rows[0];

            await client.query(
                `UPDATE queue_entries
                 SET status = 'testing',
                     tester_discord_id = $1
                 WHERE id = $2`,
                [testerDiscordId, player.id]
            );

            await client.query("COMMIT");

            res.json({
                success: true,
                message: "Player selected. Create their private ticket.",
                entry: {
                    id: player.id,
                    username: player.username,
                    uuid: player.uuid,
                    discordId: player.discord_id,
                    queueType,
                    gamemode,
                    currentTier: player.current_tier,
                    targetTier: player.target_tier,
                    region: player.region,
                    joinedAt: Number(player.joined_at),
                    testerDiscordId
                }
            });
        } catch (error) {
            await client.query("ROLLBACK");

            console.error("Queue next error:", error);

            res.status(500).json({
                success: false,
                error: "Could not select next player"
            });
        } finally {
            client.release();
        }
    }
);

// ==================================================
// TESTER: ATTACH DISCORD TICKET
// ==================================================

app.post(
    "/api/queue/ticket",
    authenticate,
    async (req, res) => {
        const entryId = Number(req.body.entryId);
        const testerDiscordId = String(req.body.testerDiscordId || "");
        const ticketChannelId = String(req.body.ticketChannelId || "");

        if (
            !Number.isSafeInteger(entryId) ||
            entryId <= 0 ||
            !validDiscordId(testerDiscordId) ||
            !validDiscordId(ticketChannelId)
        ) {
            return res.status(400).json({
                success: false,
                error: "Invalid ticket information"
            });
        }

        try {
            const result = await pool.query(
                `UPDATE queue_entries
                 SET ticket_channel_id = $1
                 WHERE id = $2
                   AND status = 'testing'
                   AND tester_discord_id = $3
                 RETURNING id`,
                [ticketChannelId, entryId, testerDiscordId]
            );

            if (!result.rows.length) {
                return res.status(404).json({
                    success: false,
                    error: "Testing entry not found or assigned to another tester"
                });
            }

            res.json({
                success: true,
                message: "Ticket linked to testing entry"
            });
        } catch (error) {
            console.error("Ticket link error:", error);

            res.status(500).json({
                success: false,
                error: "Could not link ticket"
            });
        }
    }
);

// ==================================================
// TESTER: RELEASE PLAYER
// ==================================================

app.post(
    "/api/queue/release",
    authenticate,
    async (req, res) => {
        const entryId = Number(req.body.entryId);
        const testerDiscordId = String(req.body.testerDiscordId || "");

        if (
            !Number.isSafeInteger(entryId) ||
            entryId <= 0 ||
            !validDiscordId(testerDiscordId)
        ) {
            return res.status(400).json({
                success: false,
                error: "Invalid testing entry"
            });
        }

        try {
            const result = await pool.query(
                `UPDATE queue_entries
                 SET status = 'waiting',
                     tester_discord_id = NULL,
                     ticket_channel_id = NULL
                 WHERE id = $1
                   AND status = 'testing'
                   AND tester_discord_id = $2
                 RETURNING id, queue_type, gamemode, region`,
                [entryId, testerDiscordId]
            );

            if (!result.rows.length) {
                return res.status(404).json({
                    success: false,
                    error: "Testing entry not found"
                });
            }

            res.json({
                success: true,
                message: "Player returned to the queue",
                entry: result.rows[0]
            });
        } catch (error) {
            console.error("Queue release error:", error);

            res.status(500).json({
                success: false,
                error: "Could not release testing entry"
            });
        }
    }
);

// ==================================================
// TESTER: FINISH TEST
// ==================================================

app.post(
    "/api/queue/finish",
    authenticate,
    async (req, res) => {
        const entryId = Number(req.body.entryId);
        const testerDiscordId = String(req.body.testerDiscordId || "");

        if (
            !Number.isSafeInteger(entryId) ||
            entryId <= 0 ||
            !validDiscordId(testerDiscordId)
        ) {
            return res.status(400).json({
                success: false,
                error: "Invalid testing entry"
            });
        }

        try {
            const result = await pool.query(
                `UPDATE queue_entries
                 SET status = 'completed'
                 WHERE id = $1
                   AND status = 'testing'
                   AND tester_discord_id = $2
                 RETURNING id, queue_type, gamemode, region`,
                [entryId, testerDiscordId]
            );

            if (!result.rows.length) {
                return res.status(404).json({
                    success: false,
                    error: "Testing entry not found"
                });
            }

            res.json({
                success: true,
                message: "Testing entry completed",
                entry: result.rows[0]
            });
        } catch (error) {
            console.error("Queue finish error:", error);

            res.status(500).json({
                success: false,
                error: "Could not finish testing entry"
            });
        }
    }
);

// ==================================================
// TIER ASSIGNMENT
// ==================================================

app.post(
    "/api/tiers/assign",
    authenticate,
    async (req, res) => {
        const username = String(req.body.username || "").trim();
        const gamemode = String(req.body.gamemode || "").trim();
        const tier = String(req.body.tier || "").toUpperCase().trim();

        if (!validUsername(username)) {
            return res.status(400).json({
                success: false,
                error: "Invalid username"
            });
        }

        if (!validGamemode(gamemode)) {
            return res.status(400).json({
                success: false,
                error: "Invalid gamemode"
            });
        }

        if (!TIERS.includes(tier)) {
            return res.status(400).json({
                success: false,
                error: "Invalid tier"
            });
        }

        const client = await pool.connect();

        try {
            await client.query("BEGIN");

            const playerResult = await client.query(
                `SELECT * FROM players
                 WHERE LOWER(username) = LOWER($1)
                 FOR UPDATE`,
                [username]
            );

            const player = playerResult.rows[0];

            if (!player) {
                await client.query("ROLLBACK");

                return res.status(404).json({
                    success: false,
                    error: "Player is not verified"
                });
            }

            const testingResult = await client.query(
                `SELECT id, queue_type, current_tier, target_tier
                 FROM queue_entries
                 WHERE uuid = $1
                   AND gamemode = $2
                   AND status = 'testing'
                 FOR UPDATE`,
                [player.uuid, gamemode]
            );

            const activeHT = testingResult.rows.find(
                entry => entry.queue_type === "ht"
            );

            if (activeHT) {
                if (
                    activeHT.target_tier !== tier ||
                    !validHTPath(activeHT.current_tier, tier)
                ) {
                    await client.query("ROLLBACK");

                    return res.status(400).json({
                        success: false,
                        error: "Assigned tier does not match the active HT promotion path",
                        allowedTarget: HT_PATHS[activeHT.current_tier] || null
                    });
                }
            }

            const gamemodes = player.gamemodes || createGamemodes();

            if (!gamemodes[gamemode]) {
                gamemodes[gamemode] = {
                    tested: false,
                    tier: null,
                    testedAt: null,
                    history: []
                };
            }

            const modeData = gamemodes[gamemode];

            modeData.tested = true;
            modeData.tier = tier;
            modeData.testedAt = Date.now();

            if (!Array.isArray(modeData.history)) {
                modeData.history = [];
            }

            modeData.history.push({
                tier,
                testedAt: modeData.testedAt
            });

            await client.query(
                "UPDATE players SET gamemodes = $1 WHERE uuid = $2",
                [JSON.stringify(gamemodes), player.uuid]
            );

            await client.query(
                `UPDATE queue_entries
                 SET status = 'completed'
                 WHERE uuid = $1
                   AND gamemode = $2
                   AND status = 'testing'
                   AND (target_tier IS NULL OR target_tier = $3)`,
                [player.uuid, gamemode, tier]
            );

            await client.query("COMMIT");

            res.json({
                success: true,
                message: `${player.username} received ${tier} in ${gamemode}`,
                player: {
                    username: player.username,
                    uuid: player.uuid,
                    gamemode,
                    tier,
                    testedAt: modeData.testedAt
                }
            });
        } catch (error) {
            await client.query("ROLLBACK");

            console.error("Tier assignment error:", error);

            res.status(500).json({
                success: false,
                error: "Database error"
            });
        } finally {
            client.release();
        }
    }
);

// ==================================================
// TIER REMOVAL
// ==================================================

app.delete(
    "/api/tiers/remove",
    authenticate,
    async (req, res) => {
        const username = String(req.body.username || "").trim();
        const gamemode = String(req.body.gamemode || "").trim();

        if (!validUsername(username)) {
            return res.status(400).json({
                success: false,
                error: "Invalid username"
            });
        }

        if (!validGamemode(gamemode)) {
            return res.status(400).json({
                success: false,
                error: "Invalid gamemode"
            });
        }

        try {
            const player = await findPlayerByUsername(username);

            if (!player) {
                return res.status(404).json({
                    success: false,
                    error: "Verified player not found"
                });
            }

            const gamemodes = player.gamemodes || {};
            const modeData = gamemodes[gamemode];

            if (!modeData || !modeData.tested || !modeData.tier) {
                return res.status(404).json({
                    success: false,
                    error: "Player has no assigned tier in this gamemode"
                });
            }

            const removedTier = modeData.tier;

            modeData.tested = false;
            modeData.tier = null;
            modeData.testedAt = null;

            await pool.query(
                "UPDATE players SET gamemodes = $1 WHERE uuid = $2",
                [JSON.stringify(gamemodes), player.uuid]
            );

            res.json({
                success: true,
                message: `${player.username}'s ${gamemode} tier was removed`,
                player: {
                    username: player.username,
                    uuid: player.uuid,
                    gamemode,
                    removedTier
                }
            });
        } catch (error) {
            console.error("Tier removal error:", error);

            res.status(500).json({
                success: false,
                error: "Database error"
            });
        }
    }
);

// ==================================================
// LEADERBOARD
// ==================================================

app.get("/api/tiers/leaderboard", async (req, res) => {
    res.setHeader("Cache-Control", "no-store");

    const gamemode = req.query.gamemode
        ? String(req.query.gamemode)
        : null;

    if (gamemode && !validGamemode(gamemode)) {
        return res.status(400).json({
            success: false,
            error: "Invalid gamemode",
            gamemodes: GAMEMODES
        });
    }

    try {
        const players = await getLeaderboard(gamemode);

        res.json({
            success: true,
            count: players.length,
            gamemode: gamemode || "All",
            tiers: TIERS,
            players
        });
    } catch (error) {
        console.error("Leaderboard error:", error);

        res.status(500).json({
            success: false,
            error: "Database error"
        });
    }
});

// ==================================================
// GAMEMODE, TIER AND REGION INFORMATION
// ==================================================

app.get("/api/tiers/info", (req, res) => {
    res.json({
        success: true,
        gamemodes: GAMEMODES,
        tiers: TIERS,
        regions: REGIONS,
        htPaths: HT_PATHS,
        queueTypes: ["normal", "ht"],
        maximumQueueSize: MAX_QUEUE_SIZE
    });
});

// ==================================================
// START SERVER
// ==================================================

async function startServer() {
    try {
        await initializeDatabase();

        app.listen(PORT, () => {
            console.log(`VIPTIERS backend running on port ${PORT}`);
        });
    } catch (error) {
        console.error("Could not start VIPTIERS backend:", error);
        process.exit(1);
    }
}

startServer();


const express = require("express");
const crypto = require("crypto");
const { Pool } = require("pg");

const app = express();
app.use(express.json());

const PORT = process.env.PORT || 3000;
const API_SECRET = process.env.API_SECRET;
const DATABASE_URL = process.env.DATABASE_URL;
const WEBSITE_ORIGIN = "https://suffix55.github.io";
const CODE_LIFETIME = 10 * 60 * 1000;

if (!DATABASE_URL) {
    console.error("DATABASE_URL is missing!");
    process.exit(1);
}

const pool = new Pool({
    connectionString: DATABASE_URL,
    ssl: { rejectUnauthorized: false }
});

const pendingCodes = new Map();
const usernameCooldowns = new Map();

const GAMEMODES = [
    "Crystal",
    "UHC",
    "Pot",
    "NethOP",
    "DiaSMP",
    "Sword",
    "Axe",
    "Mace",
    "Spear Mace",
    "Cart"
];

const TIERS = [
    "HT1",
    "LT1",
    "HT2",
    "LT2",
    "HT3",
    "LT3",
    "HT4",
    "LT4",
    "HT5",
    "LT5"
];

// Database setup
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

    console.log("VIPTIERS PostgreSQL database connected!");
}

// CORS
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

// Authentication
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

// Generate verification code
function generateCode() {
    for (let i = 0; i < 100; i++) {
        const code = crypto.randomInt(0, 1000000)
            .toString()
            .padStart(6, "0");

        if (!pendingCodes.has(code)) return code;
    }

    throw new Error("Could not generate code");
}

// Remove expired codes
function cleanExpiredCodes() {
    const now = Date.now();

    for (const [code, entry] of pendingCodes) {
        if (entry.expiresAt <= now) {
            pendingCodes.delete(code);
        }
    }
}

// Default gamemodes
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

// Get player by username
async function findPlayerByUsername(username) {
    const result = await pool.query(
        `SELECT * FROM players WHERE LOWER(username) = LOWER($1)`,
        [username]
    );

    return result.rows[0] || null;
}

// Get leaderboard
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

// Health check
app.get("/", (req, res) => {
    res.json({
        name: "VIPTIERS",
        status: "online",
        database: "PostgreSQL"
    });
});

// Start verification
app.post("/api/verification/start", (req, res) => {
    cleanExpiredCodes();

    const username = String(req.body.username || "").trim();

    if (!/^[a-zA-Z0-9_]{3,16}$/.test(username)) {
        return res.status(400).json({
            success: false,
            error: "Invalid Minecraft username"
        });
    }

    const normalized = username.toLowerCase();
    const lastRequest = usernameCooldowns.get(normalized);

    if (lastRequest && Date.now() - lastRequest < 30000) {
        return res.status(429).json({
            success: false,
            error: "Please wait 30 seconds before requesting another code"
        });
    }

    const code = generateCode();

    pendingCodes.set(code, {
        username,
        expiresAt: Date.now() + CODE_LIFETIME
    });

    usernameCooldowns.set(normalized, Date.now());

    res.json({
        success: true,
        username,
        code,
        expiresIn: CODE_LIFETIME / 1000
    });
});

// Complete Minecraft verification
app.post(
    "/api/verification/complete",
    authenticate,
    async (req, res) => {
        cleanExpiredCodes();

        const uuid = String(req.body.uuid || "")
            .replace(/-/g, "")
            .toLowerCase();

        const username = String(req.body.username || "").trim();
        const code = String(req.body.code || "");

        if (
            !/^[a-f0-9]{32}$/.test(uuid) ||
            !/^[a-zA-Z0-9_]{3,16}$/.test(username) ||
            !/^\d{6}$/.test(code)
        ) {
            return res.status(400).json({
                success: false,
                error: "Invalid verification details"
            });
        }

        const entry = pendingCodes.get(code);

        if (
            !entry ||
            entry.username.toLowerCase() !== username.toLowerCase()
        ) {
            return res.status(400).json({
                success: false,
                error: "Incorrect code or Minecraft username"
            });
        }

        try {
            const existing = await pool.query(
                "SELECT * FROM players WHERE uuid = $1",
                [uuid]
            );

            const existingPlayer = existing.rows[0];

            const usernameOwner = await findPlayerByUsername(username);

            if (usernameOwner && usernameOwner.uuid !== uuid) {
                return res.status(409).json({
                    success: false,
                    error: "This username is already linked to another account"
                });
            }

            const verifiedAt = existingPlayer
                ? existingPlayer.verified_at
                : Date.now();

            const gamemodes = existingPlayer
                ? existingPlayer.gamemodes
                : createGamemodes();

            await pool.query(
                `INSERT INTO players (uuid, username, verified_at, gamemodes)
                 VALUES ($1, $2, $3, $4)
                 ON CONFLICT (uuid)
                 DO UPDATE SET
                    username = EXCLUDED.username`,
                [uuid, username, verifiedAt, JSON.stringify(gamemodes)]
            );

            pendingCodes.delete(code);

            res.json({
                success: true,
                verified: true,
                message: "Your account has been verified!"
            });
        } catch (error) {
            console.error("Verification error:", error);

            res.status(500).json({
                success: false,
                error: "Could not complete verification"
            });
        }
    }
);

// Check verification status
app.get(
    "/api/verification/status/:uuid",
    authenticate,
    async (req, res) => {
        const uuid = req.params.uuid.replace(/-/g, "").toLowerCase();

        if (!/^[a-f0-9]{32}$/.test(uuid)) {
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

// Get all verified players
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

// Public player profile
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

// Revoke verification
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

// Assign a tier to a player
app.post(
    "/api/tiers/assign",
    authenticate,
    async (req, res) => {
        const username = String(req.body.username || "").trim();
        const gamemode = String(req.body.gamemode || "").trim();
        const tier = String(req.body.tier || "").toUpperCase().trim();

        if (!/^[a-zA-Z0-9_]{3,16}$/.test(username)) {
            return res.status(400).json({
                success: false,
                error: "Invalid username"
            });
        }

        if (!GAMEMODES.includes(gamemode)) {
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

        try {
            const player = await findPlayerByUsername(username);

            if (!player) {
                return res.status(404).json({
                    success: false,
                    error: "Player is not verified"
                });
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

            modeData.history.push({
                tier,
                testedAt: modeData.testedAt
            });

            await pool.query(
                "UPDATE players SET gamemodes = $1 WHERE uuid = $2",
                [JSON.stringify(gamemodes), player.uuid]
            );

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
            console.error("Tier assignment error:", error);

            res.status(500).json({
                success: false,
                error: "Database error"
            });
        }
    }
);

// Remove a player's current tier
app.delete(
    "/api/tiers/remove",
    authenticate,
    async (req, res) => {
        const username = String(req.body.username || "").trim();
        const gamemode = String(req.body.gamemode || "").trim();

        if (!/^[a-zA-Z0-9_]{3,16}$/.test(username)) {
            return res.status(400).json({
                success: false,
                error: "Invalid username"
            });
        }

        if (!GAMEMODES.includes(gamemode)) {
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

// Public leaderboard
app.get("/api/tiers/leaderboard", async (req, res) => {
    res.setHeader("Cache-Control", "no-store");

    const gamemode = req.query.gamemode
        ? String(req.query.gamemode)
        : null;

    if (gamemode && !GAMEMODES.includes(gamemode)) {
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

// Public gamemode and tier information
app.get("/api/tiers/info", (req, res) => {
    res.json({
        success: true,
        gamemodes: GAMEMODES,
        tiers: TIERS
    });
});

// Start server after database connection
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

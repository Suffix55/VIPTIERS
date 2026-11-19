
const express = require("express");
const crypto = require("crypto");

const app = express();
app.use(express.json());

const PORT = process.env.PORT || 3000;
const API_SECRET = process.env.API_SECRET;
const WEBSITE_ORIGIN = "https://suffix55.github.io";
const CODE_LIFETIME = 10 * 60 * 1000;

const pendingCodes = new Map();
const verifiedPlayers = new Map();
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

function generateCode() {
    for (let i = 0; i < 100; i++) {
        const code = crypto.randomInt(0, 1000000)
            .toString()
            .padStart(6, "0");

        if (!pendingCodes.has(code)) return code;
    }

    throw new Error("Could not generate code");
}

function cleanExpiredCodes() {
    const now = Date.now();

    for (const [code, entry] of pendingCodes) {
        if (entry.expiresAt <= now) {
            pendingCodes.delete(code);
        }
    }
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

function findPlayerByUsername(username) {
    const normalized = username.toLowerCase();

    for (const [uuid, player] of verifiedPlayers) {
        if (player.username.toLowerCase() === normalized) {
            return { uuid, ...player };
        }
    }

    return null;
}

function getLeaderboard(gamemode = null) {
    const players = [];

    for (const [uuid, player] of verifiedPlayers) {
        for (const mode of GAMEMODES) {
            if (gamemode && mode !== gamemode) continue;

            const data = player.gamemodes[mode];

            if (!data || !data.tested || !data.tier) continue;

            players.push({
                uuid,
                username: player.username,
                tier: data.tier,
                gamemode: mode,
                testedAt: data.testedAt
            });
        }
    }

    players.sort((a, b) => {
        const tierDifference =
            TIERS.indexOf(a.tier) - TIERS.indexOf(b.tier);

        if (tierDifference !== 0) return tierDifference;

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
        status: "online"
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
    (req, res) => {
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

        pendingCodes.delete(code);

        const existing = verifiedPlayers.get(uuid);

        verifiedPlayers.set(uuid, {
            username,
            verifiedAt: existing?.verifiedAt || Date.now(),
            gamemodes: existing?.gamemodes || createGamemodes()
        });

        res.json({
            success: true,
            verified: true,
            message: "Your account has been verified!"
        });
    }
);

// Check verification status
app.get(
    "/api/verification/status/:uuid",
    authenticate,
    (req, res) => {
        const uuid = req.params.uuid.replace(/-/g, "").toLowerCase();
        const player = verifiedPlayers.get(uuid);

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
            verifiedAt: player.verifiedAt
        });
    }
);

// Get all verified players
app.get(
    "/api/verification/players",
    authenticate,
    (req, res) => {
        const players = Array.from(
            verifiedPlayers.entries()
        ).map(([uuid, player]) => ({
            uuid,
            ...player
        }));

        res.json({
            success: true,
            count: players.length,
            players
        });
    }
);

// Public player profile
app.get("/api/verification/player/:username", (req, res) => {
    res.setHeader("Cache-Control", "no-store");

    const player = findPlayerByUsername(req.params.username);

    if (!player) {
        return res.status(404).json({
            success: false,
            error: "Verified player not found"
        });
    }

    res.json({
        success: true,
        player
    });
});

// Revoke verification
app.delete(
    "/api/verification/player/:username",
    authenticate,
    (req, res) => {
        const player = findPlayerByUsername(req.params.username);

        if (!player) {
            return res.status(404).json({
                success: false,
                error: "Verified player not found"
            });
        }

        verifiedPlayers.delete(player.uuid);

        res.json({
            success: true,
            message: `${player.username} has been unverified`
        });
    }
);

// Assign a tier to a player
app.post(
    "/api/tiers/assign",
    authenticate,
    (req, res) => {
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

        const player = findPlayerByUsername(username);

        if (!player) {
            return res.status(404).json({
                success: false,
                error: "Player is not verified"
            });
        }

        const playerData = verifiedPlayers.get(player.uuid);
        const modeData = playerData.gamemodes[gamemode];

        modeData.tested = true;
        modeData.tier = tier;
        modeData.testedAt = Date.now();

        modeData.history.push({
            tier,
            testedAt: modeData.testedAt
        });

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
    }
);

// Remove a player's current tier for one gamemode
app.delete(
    "/api/tiers/remove",
    authenticate,
    (req, res) => {
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

        const player = findPlayerByUsername(username);

        if (!player) {
            return res.status(404).json({
                success: false,
                error: "Verified player not found"
            });
        }

        const playerData = verifiedPlayers.get(player.uuid);
        const modeData = playerData.gamemodes[gamemode];

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
    }
);

// Public high tiers leaderboard
app.get("/api/tiers/leaderboard", (req, res) => {
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

    const players = getLeaderboard(gamemode);

    res.json({
        success: true,
        count: players.length,
        gamemode: gamemode || "All",
        tiers: TIERS,
        players
    });
});

// Public list of supported gamemodes and tiers
app.get("/api/tiers/info", (req, res) => {
    res.json({
        success: true,
        gamemodes: GAMEMODES,
        tiers: TIERS
    });
});

app.listen(PORT, () => {
    console.log(`VIPTIERS backend running on port ${PORT}`);
});

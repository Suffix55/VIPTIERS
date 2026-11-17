
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

app.use((req, res, next) => {
    const origin = req.headers.origin;

    if (origin === WEBSITE_ORIGIN) {
        res.setHeader("Access-Control-Allow-Origin", origin);
        res.setHeader("Vary", "Origin");
        res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        res.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }

    if (req.method === "OPTIONS") {
        return res.sendStatus(204);
    }

    next();
});

function authenticate(req, res, next) {
    const supplied = req.headers.authorization || "";

    if (!API_SECRET || supplied !== `Bearer ${API_SECRET}`) {
        return res.status(401).json({ error: "Unauthorized" });
    }

    next();
}

function generateCode() {
    for (let i = 0; i < 100; i++) {
        const code = crypto.randomInt(0, 1000000)
            .toString()
            .padStart(6, "0");

        if (!pendingCodes.has(code)) {
            return code;
        }
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

// Backend health check
app.get("/", (req, res) => {
    res.json({
        name: "VIPTIERS",
        status: "online"
    });
});

// Website requests a verification code
app.post("/api/verification/start", (req, res) => {
    cleanExpiredCodes();

    const username = String(req.body.username || "").trim();

    if (!/^[a-zA-Z0-9_]{3,16}$/.test(username)) {
        return res.status(400).json({
            error: "Invalid Minecraft username"
        });
    }

    const normalized = username.toLowerCase();
    const lastRequest = usernameCooldowns.get(normalized);

    if (lastRequest && Date.now() - lastRequest < 30000) {
        return res.status(429).json({
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

// Minecraft plugin confirms the code
app.post("/api/verification/complete", authenticate, (req, res) => {
    cleanExpiredCodes();

    const uuid = String(req.body.uuid || "");
    const username = String(req.body.username || "").trim();
    const code = String(req.body.code || "");

    if (!/^[a-f0-9-]{36}$/i.test(uuid) ||
        !/^[a-zA-Z0-9_]{3,16}$/.test(username) ||
        !/^\d{6}$/.test(code)) {
        return res.status(400).json({
            error: "Invalid verification details"
        });
    }

    const entry = pendingCodes.get(code);

    if (!entry || entry.username.toLowerCase() !== username.toLowerCase()) {
        return res.status(400).json({
            error: "Incorrect code or Minecraft username"
        });
    }

    pendingCodes.delete(code);

    verifiedPlayers.set(uuid, {
        username,
        verifiedAt: Date.now()
    });

    res.json({
        success: true,
        verified: true,
        message: "Your account has been verified!"
    });
});

// Plugin checks whether a player is verified
app.get("/api/verification/status/:uuid", authenticate, (req, res) => {
    const player = verifiedPlayers.get(req.params.uuid);

    if (!player) {
        return res.status(404).json({
            error: "Player not verified"
        });
    }

    res.json({
        verified: true,
        username: player.username
    });
});

app.listen(PORT, () => {
    console.log(`VIPTIERS backend running on port ${PORT}`);
});


const express = require("express");
const crypto = require("crypto");

const app = express();
app.use(express.json());

const PORT = process.env.PORT || 3000;
const API_SECRET = process.env.API_SECRET;

const CODE_LENGTH = 6;
const CODE_LIFETIME = 10 * 60 * 1000;

// Temporary storage. Persistent database will be added before launch.
const verifications = new Map();
const usedCodes = new Set();

function authenticate(req, res, next) {
    if (!API_SECRET ||
        req.headers.authorization !== `Bearer ${API_SECRET}`) {
        return res.status(401).json({
            error: "Unauthorized"
        });
    }

    next();
}

function generateCode() {
    for (let attempt = 0; attempt < 100; attempt++) {
        const code = crypto.randomInt(0, 1000000)
            .toString()
            .padStart(CODE_LENGTH, "0");

        const alreadyActive = [...verifications.values()]
            .some(entry => entry.code === code);

        if (!alreadyActive && !usedCodes.has(code)) {
            return code;
        }
    }

    throw new Error("Could not generate a unique code");
}

function cleanExpiredCodes() {
    const now = Date.now();

    for (const [uuid, entry] of verifications.entries()) {
        if (!entry.verified && entry.expiresAt <= now) {
            verifications.delete(uuid);
        }
    }
}

app.get("/", (req, res) => {
    res.json({
        name: "VIPTIERS",
        status: "online"
    });
});

// Generate a unique verification code for a player.
app.post("/api/verification/start", authenticate, (req, res) => {
    cleanExpiredCodes();

    const { uuid, username } = req.body;

    if (!uuid || !username) {
        return res.status(400).json({
            error: "Missing UUID or username"
        });
    }

    const existing = verifications.get(uuid);

    if (existing?.verified) {
        return res.status(409).json({
            error: "Player already verified"
        });
    }

    if (existing && existing.expiresAt > Date.now()) {
        return res.json({
            success: true,
            code: existing.code,
            expiresAt: existing.expiresAt
        });
    }

    let code;

    try {
        code = generateCode();
    } catch {
        return res.status(503).json({
            error: "No available codes. Try again later."
        });
    }

    const expiresAt = Date.now() + CODE_LIFETIME;

    verifications.set(uuid, {
        username,
        code,
        verified: false,
        expiresAt
    });

    res.json({
        success: true,
        code,
        expiresAt
    });
});

// Check whether a player has completed verification.
app.get("/api/verification/status/:uuid", authenticate, (req, res) => {
    cleanExpiredCodes();

    const player = verifications.get(req.params.uuid);

    if (!player) {
        return res.status(404).json({
            error: "Verification not found"
        });
    }

    res.json({
        verified: player.verified
    });
});

// Confirm a player's code.
app.post("/api/verification/complete", authenticate, (req, res) => {
    cleanExpiredCodes();

    const { uuid, code } = req.body;

    if (!uuid || !code || !/^\d{6}$/.test(code)) {
        return res.status(400).json({
            error: "Invalid UUID or code"
        });
    }

    const player = verifications.get(uuid);

    if (!player || player.code !== code) {
        return res.status(400).json({
            error: "Incorrect verification code"
        });
    }

    if (player.expiresAt <= Date.now()) {
        verifications.delete(uuid);

        return res.status(400).json({
            error: "Verification code expired"
        });
    }

    player.verified = true;
    usedCodes.add(code);

    res.json({
        success: true,
        verified: true,
        message: "Your account has been verified!"
    });
});

app.listen(PORT, () => {
    console.log(`VIPTIERS backend running on port ${PORT}`);
});

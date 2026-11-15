
const express = require("express");
const crypto = require("crypto");

const app = express();
app.use(express.json());

const PORT = process.env.PORT || 3000;
const API_SECRET = process.env.API_SECRET;

const verifications = new Map();

function authenticate(req, res, next) {
    if (!API_SECRET || req.headers.authorization !== `Bearer ${API_SECRET}`) {
        return res.status(401).json({ error: "Unauthorized" });
    }
    next();
}

app.get("/", (req, res) => {
    res.json({ name: "VIPTIERS", status: "online" });
});

// Create a verification code for a Minecraft player
app.post("/api/verification/start", authenticate, (req, res) => {
    const { uuid, username } = req.body;

    if (!uuid || !username) {
        return res.status(400).json({ error: "Missing player information" });
    }

    const code = crypto.randomInt(100000, 1000000).toString();

    verifications.set(uuid, {
        username,
        code,
        verified: false,
        createdAt: Date.now()
    });

    res.json({
        success: true,
        code
    });
});

// Check whether the player has been verified
app.get("/api/verification/status/:uuid", authenticate, (req, res) => {
    const player = verifications.get(req.params.uuid);

    if (!player) {
        return res.status(404).json({ error: "Verification not found" });
    }

    res.json({
        verified: player.verified
    });
});

app.listen(PORT, () => {
    console.log(`VIPTIERS backend running on port ${PORT}`);
});

const express = require('express');
const app = express();
const port = process.env.PORT || 3000;

app.get('/', (req, res) => {
    res.json({
        message: 'Hello from Node.js CI/CD Pipeline!',
        version: process.env.APP_VERSION || '1.0.0',
        timestamp: new Date().toISOString()
    });
});

app.get('/health', (req, res) => {
    res.json({ status: 'healthy' });
});

/*app.listen(port, () => {
    console.log(`Server running on port ${port}`);
});*/

module.exports = app;
# Cloudflare Worker

This directory contains GuardianLink's Worker API, Wrangler configuration, and Node.js tests. Firebase remains the identity and data layer for the configured project; the Worker handles the HTTP and media integration configured here.

## Requirements

- Node.js 18 or newer
- npm
- A Cloudflare account for deployment

## Install and Test

Run these commands from this directory:

```powershell
npm ci
npm test
```

For local development, run `npm run dev`.

## Configure Secrets

Set the Worker secrets with Wrangler. Never put private-key values in source files, command history, or committed configuration:

```powershell
npx wrangler secret put FIREBASE_SERVICE_ACCOUNT_EMAIL
npx wrangler secret put FIREBASE_SERVICE_ACCOUNT_PRIVATE_KEY
```

Use a dedicated service account with only the permissions required by the Worker. Review the Worker implementation and Firebase IAM permissions before deployment.

## Deploy

The project configuration is in `wrangler.toml`. Create the configured R2 bucket if it does not already exist, then deploy:

```powershell
npx wrangler r2 bucket create guardianlink-safe-media
npx wrangler deploy
```

The bucket command will report an error if that bucket already exists; verify that an existing bucket belongs to the intended Cloudflare account. Configure the Android Gradle property `CLOUDFLARE_WORKER_URL` to the deployed endpoint when building for a different environment.

Review the current Cloudflare plan limits, Firebase permissions, data-retention behavior, and access controls before using production data. Usage limits and prices can change; consult the providers' current documentation.

# Voxen docs

Documentation site for Voxen, built with [Astro](https://astro.build) and
[Starlight](https://starlight.astro.build).

## Running it

```bash
npm install
npm run dev      # http://localhost:4321
npm run build    # static output in ./dist
npm run preview  # serve the build
```

## Layout

```
src/content/docs/
├── index.mdx           landing page
├── start/              introduction, installation, quick start
├── guides/             commands, channels, formats, player formatting
├── configuration/      one page per config file
├── network/            cross-server chat
├── integrations/       PlaceholderAPI, MiniPlaceholders
└── api/                developer API and events
```

Pages are MDX. Adding one means dropping a file in the right folder and listing its slug
in the sidebar in `astro.config.mjs`.

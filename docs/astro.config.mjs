// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import catppuccin from '@catppuccin/starlight';

// https://astro.build/config
export default defineConfig({
	integrations: [
		starlight({
			title: 'Voxen',
			description:
				'Chat plugin for PaperMC 1.21.8+. Channels, private messages, parties, moderation, ' +
				'mentions, nicknames and optional cross-server chat over Redis, NATS or RabbitMQ.',
			logo: {
				src: './src/assets/logo.svg',
				alt: 'Voxen',
			},
			social: [
				{
					icon: 'github',
					label: 'GitHub',
					href: 'https://github.com/Naimadx123/Voxen',
				},
				{
					icon: 'discord',
					label: 'Discord',
					href: 'https://discord.gg/aSRYxqSjVJ',
				},
			],
			editLink: {
				baseUrl: 'https://github.com/Naimadx123/Voxen/edit/master/docs/',
			},
			lastUpdated: true,
			tableOfContents: { minHeadingLevel: 2, maxHeadingLevel: 3 },
			sidebar: [
				{
					label: 'Start here',
					items: [
						{ label: 'Introduction', slug: 'start/introduction' },
						{ label: 'Installation', slug: 'start/installation' },
						{ label: 'Quick start', slug: 'start/quick-start' },
					],
				},
				{
					label: 'Using Voxen',
					items: [
						{ label: 'Commands & permissions', slug: 'guides/commands' },
						{ label: 'Channels', slug: 'guides/channels' },
						{ label: 'Chat formats', slug: 'guides/formats' },
						{ label: 'What players may type', slug: 'guides/player-formatting' },
					],
				},
				{
					label: 'Configuration',
					items: [
						{ label: 'Overview', slug: 'configuration/overview' },
						{ label: 'config.yml', slug: 'configuration/config' },
						{ label: 'Moderation', slug: 'configuration/moderation' },
						{ label: 'Other modules', slug: 'configuration/modules' },
						{ label: 'Player reports', slug: 'configuration/reports' },
						{ label: 'System messages', slug: 'configuration/system-messages' },
						{ label: 'Web panel', slug: 'configuration/web' },
						{ label: 'Messages & languages', slug: 'configuration/messages' },
						{ label: 'Storage', slug: 'configuration/storage' },
					],
				},
				{
					label: 'Network',
					badge: { text: 'Multi-server', variant: 'note' },
					items: [
						{ label: 'Cross-server chat', slug: 'network/cross-server' },
					],
				},
				{
					label: 'Integrations',
					items: [
						{ label: 'integrations.yml', slug: 'integrations/overview' },
						{ label: 'PlaceholderAPI', slug: 'integrations/placeholderapi' },
						{ label: 'MiniPlaceholders', slug: 'integrations/miniplaceholders' },
					],
				},
				{
					label: 'Developer API',
					badge: { text: 'Addons', variant: 'note' },
					items: [
						{ label: 'Overview', slug: 'api/overview' },
						{ label: 'Events', slug: 'api/events' },
					],
				},
			],
			plugins: [
				catppuccin({
					dark: { flavor: 'mocha', accent: 'sky' },
					light: { flavor: 'latte', accent: 'sky' },
				}),
			],
		}),
	],
});

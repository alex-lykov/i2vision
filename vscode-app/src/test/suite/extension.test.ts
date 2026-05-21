/**
 * Unit tests for i2-Vision VSCode Extension
 */

import * as assert from 'assert';
import * as vscode from 'vscode';
import { I2VisionCLI, DiscoveryResult, TemplateInfo } from '../../cliIntegration';
import { I2VisionTreeProvider, I2VisionTreeItem } from '../../treeViewProvider';

suite('i2-Vision Extension Tests', () => {
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';

    suite('CLI Integration Tests', () => {
        let cli: I2VisionCLI;

        setup(() => {
            cli = new I2VisionCLI(workspaceRoot);
        });

        test('CLI should initialize with workspace root', () => {
            assert.ok(cli);
        });

        test('Discovery should return mock data when CLI unavailable', async () => {
            const result = await cli.runDiscovery();
            
            assert.ok(result);
            assert.ok(result.projectName);
            assert.ok(Array.isArray(result.components));
            assert.ok(Array.isArray(result.relationships));
            assert.ok(Array.isArray(result.layers));
        });

        test('Discovery result should have valid structure', async () => {
            const result = await cli.runDiscovery();
            
            assert.strictEqual(typeof result.projectName, 'string');
            assert.strictEqual(typeof result.version, 'string');
            assert.ok(result.components.length >= 0);
            
            // Check component structure
            if (result.components.length > 0) {
                const component = result.components[0];
                assert.ok(component.name);
                assert.ok(component.type);
                assert.ok(component.path);
            }
        });

        test('Templates should return mock data', async () => {
            const templates = await cli.listTemplates();
            
            assert.ok(Array.isArray(templates));
            assert.ok(templates.length > 0);
            
            // Check template structure
            const template = templates[0];
            assert.ok(template.name);
            assert.ok(template.category);
            assert.ok(Array.isArray(template.files));
            assert.ok(Array.isArray(template.variables));
        });

        test('Context should return data for file path', async () => {
            const context = await cli.getContext('test/file.kt');
            
            assert.ok(context);
            assert.ok(context.filePath);
            assert.ok(context.component);
            assert.ok(context.layer);
            assert.ok(Array.isArray(context.dependencies));
        });

        test('CLI availability check', async () => {
            const available = await cli.isAvailable();
            // Should return false in test environment (no CLI installed)
            assert.strictEqual(typeof available, 'boolean');
        });
    });

    suite('Tree Provider Tests', () => {
        let treeProvider: I2VisionTreeProvider;

        setup(() => {
            treeProvider = new I2VisionTreeProvider(workspaceRoot);
        });

        test('Tree provider should initialize', () => {
            assert.ok(treeProvider);
        });

        test('Root items should be returned', async () => {
            const items = await treeProvider.getChildren();
            
            assert.ok(Array.isArray(items));
            assert.ok(items.length > 0);
            
            // Check for expected root items
            const itemTypes = items.map(item => item.itemType);
            assert.ok(itemTypes.includes('projects'));
            assert.ok(itemTypes.includes('templates'));
            assert.ok(itemTypes.includes('settings'));
            assert.ok(itemTypes.includes('documentation'));
        });

        test('Projects should load with discovery data', async () => {
            const rootItems = await treeProvider.getChildren();
            const projectsItem = rootItems.find(item => item.itemType === 'projects');
            
            assert.ok(projectsItem);
            
            const projectItems = await treeProvider.getChildren(projectsItem);
            assert.ok(Array.isArray(projectItems));
            
            // Should have at least the main project
            if (projectItems.length > 0) {
                const projectItem = projectItems[0];
                assert.ok(projectItem.label);
                assert.strictEqual(projectItem.itemType, 'project');
            }
        });

        test('Templates should load', async () => {
            const rootItems = await treeProvider.getChildren();
            const templatesItem = rootItems.find(item => item.itemType === 'templates');
            
            assert.ok(templatesItem);
            
            const templateItems = await treeProvider.getChildren(templatesItem);
            assert.ok(Array.isArray(templateItems));
            assert.ok(templateItems.length > 0);
            
            // Check template structure
            const templateItem = templateItems[0];
            assert.ok(templateItem.label);
            assert.strictEqual(templateItem.itemType, 'template');
        });

        test('Settings items should be returned', async () => {
            const rootItems = await treeProvider.getChildren();
            const settingsItem = rootItems.find(item => item.itemType === 'settings');
            
            assert.ok(settingsItem);
            
            const settingsItems = await treeProvider.getChildren(settingsItem);
            assert.ok(Array.isArray(settingsItems));
            assert.ok(settingsItems.length > 0);
        });

        test('Documentation items should be returned', async () => {
            const rootItems = await treeProvider.getChildren();
            const docsItem = rootItems.find(item => item.itemType === 'documentation');
            
            assert.ok(docsItem);
            
            const docsItems = await treeProvider.getChildren(docsItem);
            assert.ok(Array.isArray(docsItems));
            assert.ok(docsItems.length > 0);
        });

        test('Tree item should have correct properties', () => {
            const item = new I2VisionTreeItem(
                'Test Item',
                'component',
                vscode.TreeItemCollapsibleState.Collapsed,
                { test: 'data' }
            );

            assert.strictEqual(item.label, 'Test Item');
            assert.strictEqual(item.itemType, 'component');
            assert.strictEqual(item.collapsibleState, vscode.TreeItemCollapsibleState.Collapsed);
            assert.deepStrictEqual(item.metadata, { test: 'data' });
            assert.ok(item.iconPath);
        });

        test('Tree provider refresh should work', () => {
            let refreshFired = false;
            
            treeProvider.onDidChangeTreeData(() => {
                refreshFired = true;
            });
            
            treeProvider.refresh();
            assert.strictEqual(refreshFired, true);
        });

        test('Discovery cache should be accessible', async () => {
            // First access should populate cache
            await treeProvider.getChildren();
            const rootItems = await treeProvider.getChildren();
            const projectsItem = rootItems.find(item => item.itemType === 'projects');
            
            if (projectsItem) {
                await treeProvider.getChildren(projectsItem);
            }
            
            const cache = treeProvider.getDiscoveryCache();
            assert.ok(cache);
            assert.ok(cache.projectName);
        });
    });

    suite('Command Registration Tests', () => {
        test('Extension should register commands', async () => {
            // Get all registered commands
            const commands = await vscode.commands.getCommands(true);
            
            // Check for i2vision commands
            const i2visionCommands = commands.filter(cmd => cmd.startsWith('i2vision.'));
            
            assert.ok(i2visionCommands.length > 0);
            assert.ok(i2visionCommands.includes('i2vision.helloWorld'));
            assert.ok(i2visionCommands.includes('i2vision.refreshTree'));
            assert.ok(i2visionCommands.includes('i2vision.createProject'));
            assert.ok(i2visionCommands.includes('i2vision.openProject'));
        });

        test('Hello World command should execute', async () => {
            // This test verifies the command can be executed without errors
            await vscode.commands.executeCommand('i2vision.helloWorld');
            // If we get here without exception, the test passes
            assert.ok(true);
        });

        test('Refresh Tree command should execute', async () => {
            await vscode.commands.executeCommand('i2vision.refreshTree');
            assert.ok(true);
        });
    });
});

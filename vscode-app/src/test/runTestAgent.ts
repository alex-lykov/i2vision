/**
 * Test runner for AgentBridge smoke tests only.
 */

import * as path from 'path';
import Mocha from 'mocha';

export function run(): Promise<void> {
    const mocha = new Mocha({
        ui: 'tdd',
        color: true,
        timeout: 60000,
    });

    const testsRoot = path.resolve(__dirname);

    return new Promise((c, e) => {
        mocha.addFile(path.resolve(testsRoot, 'AgentBridge.test.js'));

        try {
            mocha.run(failures => {
                if (failures > 0) {
                    e(new Error(`${failures} tests failed.`));
                } else {
                    c();
                }
            });
        } catch (err) {
            console.error(err);
            e(err);
        }
    });
}

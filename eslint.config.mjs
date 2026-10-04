import eslint from "@eslint/js";
import typescriptEslint from "typescript-eslint";

const sourceFiles = [
    "packages/api-types/src/**/*.ts",
    "apps/web/src/**/*.{ts,tsx}",
    "apps/miniprogram/**/*.ts",
];

export default typescriptEslint.config(
    {
        ignores: [
            "**/node_modules/**",
            // OpenAPI owns target/; deterministic generation and compile gates verify it.
            "**/target/**",
        ],
    },
    {
        ...eslint.configs.recommended,
        files: sourceFiles,
    },
    ...typescriptEslint.configs.recommended.map((configuration) => ({
        ...configuration,
        files: sourceFiles,
    })),
);

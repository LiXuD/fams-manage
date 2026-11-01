import eslint from "@eslint/js";
import typescriptEslint from "typescript-eslint";
import vue from "eslint-plugin-vue";

const sourceFiles = [
    "packages/api-types/src/**/*.ts",
    "apps/web/src/**/*.{ts,tsx,vue}",
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
    // vue/essential：模板安全底线（no-v-html、require-v-for-key、no-use-v-if-with-v-for、no-mutating-props）。
    ...vue.configs["flat/essential"],
    {
        ...eslint.configs.recommended,
        files: sourceFiles,
    },
    ...typescriptEslint.configs.recommended.map((configuration) => ({
        ...configuration,
        files: sourceFiles,
    })),
    {
        // 必须排在 TS 块之后：.vue 由 vue-eslint-parser 解析模板，<script> 块再交给 TS 解析器。
        files: ["apps/web/src/**/*.vue"],
        languageOptions: {
            parser: vue.configs["flat/base"].find((entry) => entry.languageOptions?.parser).languageOptions.parser,
            parserOptions: {
                ecmaVersion: "latest",
                sourceType: "module",
                parser: typescriptEslint.parser,
                extraFileExtensions: [".vue"],
            },
        },
        rules: {
            "vue/multi-word-component-names": "off",
            "vue/component-name-in-template-casing": ["error", "PascalCase"],
            // essential 档不含此规则，但它是最易被模板改写重新引入的 XSS 面，单独钉住。
            "vue/no-v-html": "error",
        },
    },
);

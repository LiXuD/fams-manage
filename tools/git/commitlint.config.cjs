const allowedTypes = [
  'feat',
  'fix',
  'refactor',
  'perf',
  'docs',
  'test',
  'build',
  'ci',
  'chore',
  'style',
  'revert',
];

module.exports = {
  extends: ['@commitlint/config-conventional'],
  defaultIgnores: false,
  rules: {
    'type-enum': [2, 'always', allowedTypes],
    'scope-empty': [2, 'never'],
    'subject-empty': [2, 'never'],
    'subject-case': [0],
    'subject-non-generic': [2, 'never'],
  },
  plugins: [
    {
      rules: {
        'subject-non-generic': ({ subject }) => {
          const generic = /^(?:update(?: code)?|fix(?: issue)?|changes?|更新代码|修复问题|修改内容)$/i;
          return [Boolean(subject && !generic.test(subject.trim())), 'summary must describe the change'];
        },
      },
    },
  ],
};

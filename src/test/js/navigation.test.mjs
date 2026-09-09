// Run: node --experimental-vm-modules src/test/js/navigation.test.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createContext, SourceTextModule } from 'node:vm';

const scripts = new URL('../../main/resources/static/assets/js/', import.meta.url);
const context = createContext({
  URL,
  URLSearchParams,
  location: { pathname: '/post.html', search: '?id=17&keyword=Spring&page=2&size=50', hash: '#comments' }
});
const ui = new SourceTextModule(await readFile(new URL('ui.js', scripts), 'utf8'), { context });
const navigation = new SourceTextModule(await readFile(new URL('navigation.js', scripts), 'utf8'), { context });
await navigation.link(specifier => {
  assert.equal(specifier, './ui.js');
  return ui;
});
await navigation.evaluate();
const { safeReturnUrl, loginReturnUrl, loginUrl, signupUrl, writeUrl, editUrl } = navigation.namespace;

const safeCases = [
  ['/', '/'],
  ['/index.html?keyword=%20Spring%20Boot%20&page=3&size=50&id=2&preview=1#main', '/?keyword=Spring+Boot&page=3&size=50'],
  ['/?keyword=%25_%26&page=0&size=20&returnTo=https://evil.example', '/?keyword=%25_%26'],
  ['/?page=-1&size=100&preview=1', '/'],
  ['/?page=2147483647&size=10', '/?page=2147483647&size=10'],
  ['/?page=2147483648&size=20', '/'],
  ['/post.html?id=17&keyword=%ED%95%9C%EA%B8%80&page=2&size=10&preview=1&next=https://evil.example#comments', '/post.html?id=17&keyword=%ED%95%9C%EA%B8%80&page=2&size=10#comments'],
  ['/post.html?id=9223372036854775807', '/post.html?id=9223372036854775807'],
  ['/post.html?id=5#unknown', '/post.html?id=5'],
  ['/write.html', '/write.html'],
  ['/write.html?keyword=%20Spring%20Boot%20&page=3&size=50&id=17&preview=1&unknown=value#comments', '/write.html?keyword=Spring+Boot&page=3&size=50'],
  ['/write.html?page=-1&size=100&returnTo=https://evil.example', '/write.html'],
  ['/write.html?keyword=%25_%26&page=2147483647&size=10', '/write.html?keyword=%25_%26&page=2147483647&size=10'],
  ['/edit.html?id=17', '/edit.html?id=17'],
  ['/edit.html?id=17&keyword=%20Spring%20Boot%20&page=2&size=50&preview=1&next=https://evil.example#comments', '/edit.html?id=17&keyword=Spring+Boot&page=2&size=50'],
  ['/edit.html?id=9223372036854775807#comments', '/edit.html?id=9223372036854775807'],
  ['/edit.html?id=5&page=2147483648&size=20#unknown', '/edit.html?id=5'],
  ['/edit.html?id=5&keyword=%ED%95%9C%EA%B8%80&page=2147483647&size=10', '/edit.html?id=5&keyword=%ED%95%9C%EA%B8%80&page=2147483647&size=10']
];
const rejected = [
  null, undefined, '', 'https://evil.example/', 'http://localhost:8080/post.html?id=1',
  '//evil.example/post.html?id=1', '/\\evil.example', '/%5cevil.example',
  '/index.html?keyword=%0d%0aInjected', '/index.html\u0000', '/index.html\n',
  '/login.html?returnTo=/', '/signup.html?returnTo=/', '/api/posts', '/api/files/1/download', '/unknown.html',
  '/unknown/../post.html?id=1', '/%70ost.html?id=1',
  '/post.html', '/post.html?id=0', '/post.html?id=-1', '/post.html?id=01',
  '/post.html?id=1.5', '/post.html?id=1e2', '/post.html?id=9223372036854775808',
  '/post.html?id=99999999999999999999', '/post.html?id=1&keyword=%',
  '/write.html/../post.html?id=1', '/%77rite.html', '/write.html?keyword=%0aInjected',
  '//evil.example/write.html', '/write.html\\evil', '/write.html?keyword=%',
  '/edit.html', '/edit.html?id=0', '/edit.html?id=-1', '/edit.html?id=01',
  '/edit.html?id=1.5', '/edit.html?id=1e2', '/edit.html?id=9223372036854775808',
  '/edit.html?id=99999999999999999999', '/edit.html?id=2&keyword=%0aInjected',
  '/edit.html?id=2&keyword=%', '/%65dit.html?id=2', '/unknown/../edit.html?id=2',
  '//evil.example/edit.html?id=2', '/edit.html\\evil?id=2'
];
let passed = 0;
for (const [input, expected] of safeCases) {
  assert.equal(safeReturnUrl(input), expected, `Unexpected normalization: ${input}`);
  passed++;
}
for (const input of rejected) {
  assert.equal(safeReturnUrl(input), '/', `Unsafe target accepted: ${input}`);
  passed++;
}
const longKeyword = safeReturnUrl('/?keyword=' + 'a'.repeat(101));
assert.equal(new URL(longKeyword, 'https://board.invalid').searchParams.get('keyword').length, 100);
passed++;
for (const [input, expected] of [
  ['https://example.org', 'https://example.org/'],
  ['http://example.org/next?view=board#content', 'http://example.org/next?view=board#content'],
  ['HTTPS://EXAMPLE.ORG/next', 'https://example.org/next'],
  ['/post.html?id=5&preview=1#comments', '/post.html?id=5#comments'],
  ['javascript:alert(1)', '/'],
  ['data:text/html,content', '/'],
  ['//example.org/', '/'],
  ['https://', '/'],
  ['https://example.org/\nnext', '/'],
  ['https://example.org/\\next', '/'],
  [null, '/']
]) {
  assert.equal(loginReturnUrl(input), expected, `Unexpected login destination: ${input}`);
  passed++;
}
assert.equal(loginUrl('https://example.org'), '/login.html?returnTo=https%3A%2F%2Fexample.org%2F');
passed++;
assert.equal(loginUrl('/post.html?id=5#comments'), '/login.html?returnTo=%2Fpost.html%3Fid%3D5%23comments');
passed++;
assert.equal(loginUrl(), '/login.html?returnTo=%2Fpost.html%3Fid%3D17%26keyword%3DSpring%26page%3D2%26size%3D50%23comments');
passed++;
assert.equal(signupUrl('https://example.org'), '/signup.html?returnTo=https%3A%2F%2Fexample.org%2F');
passed++;
assert.equal(signupUrl('/post.html?id=5&preview=1#comments'), '/signup.html?returnTo=%2Fpost.html%3Fid%3D5%23comments');
passed++;
assert.equal(signupUrl(), '/signup.html?returnTo=%2Fpost.html%3Fid%3D17%26keyword%3DSpring%26page%3D2%26size%3D50%23comments');
passed++;
assert.equal(writeUrl(), '/write.html?keyword=Spring&page=2&size=50');
passed++;
assert.equal(writeUrl(''), '/write.html');
passed++;
assert.equal(writeUrl('?id=17&preview=1&keyword=%20%ED%95%9C%EA%B8%80%20&page=2&size=10&unknown=value'), '/write.html?keyword=%ED%95%9C%EA%B8%80&page=2&size=10');
passed++;
assert.equal(writeUrl('?page=2147483648&size=20&preview=1'), '/write.html');
passed++;
assert.equal(new URL(writeUrl('?keyword=' + 'a'.repeat(101)), 'https://board.invalid').searchParams.get('keyword').length, 100);
passed++;
assert.equal(loginUrl('/write.html?keyword=Spring&page=2&size=50&preview=1'), '/login.html?returnTo=%2Fwrite.html%3Fkeyword%3DSpring%26page%3D2%26size%3D50');
passed++;
assert.equal(signupUrl('/write.html?keyword=Spring&page=2&size=50&id=9'), '/signup.html?returnTo=%2Fwrite.html%3Fkeyword%3DSpring%26page%3D2%26size%3D50');
passed++;
context.location = { pathname: '/write.html', search: '?keyword=Spring&page=2&size=50&preview=1', hash: '#comments' };
assert.equal(loginUrl(), '/login.html?returnTo=%2Fwrite.html%3Fkeyword%3DSpring%26page%3D2%26size%3D50');
passed++;
assert.equal(signupUrl(), '/signup.html?returnTo=%2Fwrite.html%3Fkeyword%3DSpring%26page%3D2%26size%3D50');
passed++;

for (const [id, query, expected] of [
  [7, '', '/edit.html?id=7'],
  ['9223372036854775807', '', '/edit.html?id=9223372036854775807'],
  [9223372036854775807n, '', '/edit.html?id=9223372036854775807'],
  ['17', '?id=999&keyword=%20Spring%20Boot%20&page=2&size=10&preview=1&next=https://evil.example', '/edit.html?id=17&keyword=Spring+Boot&page=2&size=10'],
  ['17', '?page=2147483648&size=20', '/edit.html?id=17']
]) {
  assert.equal(editUrl(id, query), expected);
  passed++;
}
for (const invalid of [undefined, null, 0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, '01', '1e2', '9223372036854775808', '17&preview=1', [], {}]) {
  assert.equal(editUrl(invalid, '?keyword=Spring'), '/');
  passed++;
}
assert.equal(editUrl('17'), '/edit.html?id=17&keyword=Spring&page=2&size=50');
passed++;
assert.equal(new URL(editUrl('17', '?keyword=' + 'a'.repeat(101)), 'https://board.invalid').searchParams.get('keyword').length, 100);
passed++;
assert.equal(loginUrl('/edit.html?id=17&keyword=Spring&page=2&size=10&preview=1#comments'), '/login.html?returnTo=%2Fedit.html%3Fid%3D17%26keyword%3DSpring%26page%3D2%26size%3D10');
passed++;
assert.equal(signupUrl('/edit.html?id=17&keyword=Spring&page=2&size=10&preview=1#comments'), '/signup.html?returnTo=%2Fedit.html%3Fid%3D17%26keyword%3DSpring%26page%3D2%26size%3D10');
passed++;
context.location = { pathname: '/edit.html', search: '?id=17&keyword=Spring&page=2&size=10&preview=1', hash: '#comments' };
assert.equal(loginUrl(), '/login.html?returnTo=%2Fedit.html%3Fid%3D17%26keyword%3DSpring%26page%3D2%26size%3D10');
passed++;
assert.equal(signupUrl(), '/signup.html?returnTo=%2Fedit.html%3Fid%3D17%26keyword%3DSpring%26page%3D2%26size%3D10');
passed++;

export const result = { passed };
console.log(`navigation: ${passed} checks passed`);

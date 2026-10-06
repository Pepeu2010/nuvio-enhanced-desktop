const { test } = require('node:test');
const assert = require('node:assert/strict');
const { normalize, createRenderer } = require('../composeApp/src/desktopMain/resources/player-ui/timed-metadata.js');

const marker = { id: 'intro', kind: 'INTRO', startFraction: 0.1, endFraction: 0.2, label: 'Abertura', providerId: 'introdb' };
test('untrusted markers cannot inject markup, CSS or invalid positions', () => {
  const malicious = { ...marker, label: '<img src=x onerror=alert(1)>', kind: 'INTRO;color:red' };
  assert.deepEqual(normalize([malicious, {...marker, startFraction: NaN}, {...marker, endFraction: Infinity}, {...marker, endFraction: .01}, {...marker, kind: 'ACTOR'}]), []);
  assert.equal(normalize([{...marker, label: '<script>unsafe</script>'}])[0].label, '<script>unsafe</script>');
  assert.equal(normalize(Array(1000).fill(marker)).length, 256);
});
test('actual renderer clears old episode markers and only exposes real labels through text', () => {
  let replacements = 0;
  const elements = [];
  const document = { createDocumentFragment: () => ({children:[], appendChild(child) {this.children.push(child);}}),
    createElement: () => { const e = { dataset:{}, style:{} }; elements.push(e); return e; } };
  const layer = { ownerDocument: document, replaceChildren(fragment) { this.children = fragment.children; replacements++; } };
  const summary = { id:'events', textContent:'' };
  const seek = { attributes:{}, setAttribute(key,value) { this.attributes[key]=value; }, removeAttribute(key) {delete this.attributes[key];} };
  const render = createRenderer(layer, summary, seek);
  const text = '<svg onload=alert(1)>';
  render([{...marker,label:text}]);
  assert.equal(summary.textContent, text);
  assert.equal(layer.children[0].style.left,'10%');
  assert.equal(layer.children[0].title,text);
  assert.equal(layer.children[0].innerHTML,undefined);
  assert.equal(seek.attributes['aria-describedby'],'events');
  render([{...marker,label:text}]);
  assert.equal(replacements,1);
  render([]);
  assert.equal(layer.children.length,0);
  assert.equal(summary.textContent,'');
  assert.equal(seek.attributes['aria-describedby'],undefined);
  assert.equal(layer.hidden,true);
});

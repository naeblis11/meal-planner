// node --test static/aisle-picker.test.js
const test = require('node:test');
const assert = require('node:assert');
const { aisleOptions } = require('./aisle-picker.js');

const AISLES = ['Produce', 'Dairy & Eggs', 'Spices & Baking', 'Household'];

test('an empty field offers every aisle', () => {
  assert.deepStrictEqual(aisleOptions(AISLES, ''), AISLES);
  assert.deepStrictEqual(aisleOptions(AISLES, '   '), AISLES);
});

test('typing narrows to the aisles containing the text, ignoring case', () => {
  assert.deepStrictEqual(aisleOptions(AISLES, 'dair'), ['Dairy & Eggs']);
  assert.deepStrictEqual(aisleOptions(AISLES, 'S'), ['Dairy & Eggs', 'Spices & Baking', 'Household']);
});

test('an exact aisle offers every aisle again, so it can be changed', () => {
  assert.deepStrictEqual(aisleOptions(AISLES, 'produce'), AISLES);
});

test('a custom aisle with no match offers nothing', () => {
  assert.deepStrictEqual(aisleOptions(AISLES, 'Bulk'), []);
});

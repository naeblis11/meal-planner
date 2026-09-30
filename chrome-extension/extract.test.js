const test = require("node:test");
const assert = require("node:assert/strict");
const {
  flattenJsonLdNodes,
  isRecipeNode,
  normalizeInstructions,
  normalizeImage,
  normalizeYield,
  normalizeAuthor,
} = require("./extract.js");

test("flattenJsonLdNodes flattens a @graph array", () => {
  const input = { "@graph": [{ "@type": "WebSite" }, { "@type": "Recipe", name: "Soup" }] };
  assert.equal(flattenJsonLdNodes(input).length, 2);
});

test("flattenJsonLdNodes flattens a top-level array", () => {
  const input = [{ "@type": "Recipe" }, { "@type": "Organization" }];
  assert.equal(flattenJsonLdNodes(input).length, 2);
});

test("flattenJsonLdNodes wraps a single plain object", () => {
  assert.equal(flattenJsonLdNodes({ "@type": "Recipe" }).length, 1);
});

test("isRecipeNode matches a plain Recipe type", () => {
  assert.equal(isRecipeNode({ "@type": "Recipe" }), true);
});

test("isRecipeNode matches Recipe within a type array", () => {
  assert.equal(isRecipeNode({ "@type": ["Article", "Recipe"] }), true);
});

test("isRecipeNode rejects unrelated types", () => {
  assert.equal(isRecipeNode({ "@type": "WebPage" }), false);
});

test("isRecipeNode rejects a node with no @type", () => {
  assert.equal(isRecipeNode({ name: "no type here" }), false);
});

test("normalizeInstructions handles a plain string", () => {
  assert.deepEqual(normalizeInstructions("Do the thing."), ["Do the thing."]);
});

test("normalizeInstructions handles an array of strings", () => {
  assert.deepEqual(normalizeInstructions(["Step 1", "Step 2"]), ["Step 1", "Step 2"]);
});

test("normalizeInstructions handles HowToStep objects", () => {
  const input = [
    { "@type": "HowToStep", text: "Preheat oven." },
    { "@type": "HowToStep", text: "Bake." },
  ];
  assert.deepEqual(normalizeInstructions(input), ["Preheat oven.", "Bake."]);
});

test("normalizeInstructions flattens HowToSection groups, dropping the section name", () => {
  const input = [
    {
      "@type": "HowToSection",
      name: "For the crust",
      itemListElement: [{ "@type": "HowToStep", text: "Mix flour and butter." }],
    },
    { "@type": "HowToStep", text: "Bake at 350F." },
  ];
  assert.deepEqual(normalizeInstructions(input), ["Mix flour and butter.", "Bake at 350F."]);
});

test("normalizeInstructions returns an empty array for an unrecognized shape", () => {
  assert.deepEqual(normalizeInstructions(42), []);
});

test("normalizeImage handles a plain string", () => {
  assert.equal(normalizeImage("https://example.com/a.jpg"), "https://example.com/a.jpg");
});

test("normalizeImage handles an array of strings, taking the first", () => {
  assert.equal(
    normalizeImage(["https://example.com/a.jpg", "https://example.com/b.jpg"]),
    "https://example.com/a.jpg"
  );
});

test("normalizeImage handles an ImageObject", () => {
  assert.equal(
    normalizeImage({ "@type": "ImageObject", url: "https://example.com/a.jpg" }),
    "https://example.com/a.jpg"
  );
});

test("normalizeImage handles an array of ImageObjects", () => {
  assert.equal(
    normalizeImage([{ "@type": "ImageObject", url: "https://example.com/a.jpg" }]),
    "https://example.com/a.jpg"
  );
});

test("normalizeImage returns null when absent", () => {
  assert.equal(normalizeImage(undefined), null);
});

test("normalizeYield takes the first entry of an array", () => {
  assert.equal(normalizeYield(["4 servings", "4"]), "4 servings");
});

test("normalizeYield passes through a plain string", () => {
  assert.equal(normalizeYield("6"), "6");
});

test("normalizeYield returns null when absent", () => {
  assert.equal(normalizeYield(undefined), null);
});

test("normalizeAuthor reads a single author object's name", () => {
  assert.equal(normalizeAuthor({ name: "Jane Doe" }), "Jane Doe");
});

test("normalizeAuthor reads the first entry of an author array", () => {
  assert.equal(normalizeAuthor([{ name: "Jane Doe" }, { name: "John Smith" }]), "Jane Doe");
});

test("normalizeAuthor passes through a plain string", () => {
  assert.equal(normalizeAuthor("Jane Doe"), "Jane Doe");
});

test("normalizeAuthor returns null when absent", () => {
  assert.equal(normalizeAuthor(undefined), null);
});

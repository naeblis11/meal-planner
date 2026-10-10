const test = require("node:test");
const assert = require("node:assert/strict");
const {
  flattenJsonLdNodes,
  isRecipeNode,
  findRecipeNode,
  normalizeInstructions,
  normalizeImage,
  normalizeYield,
  normalizeAuthor,
  decodeHtml,
  cleanText,
  extractRecipeFromPage,
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

// Shapes real sites emit that the normalisation once dropped (review, 2026-10-10).

test("flattenJsonLdNodes descends into a WebPage's mainEntity, keeping the page too", () => {
  const input = { "@type": "WebPage", mainEntity: { "@type": "Recipe", name: "Soup" } };
  const nodes = flattenJsonLdNodes(input);
  assert.deepEqual(nodes.map((n) => n["@type"]), ["WebPage", "Recipe"]);
});

test("flattenJsonLdNodes descends into a mainEntity array and a mainEntityOfPage object", () => {
  const input = {
    "@graph": [
      { "@type": "WebPage", mainEntity: [{ "@type": "Article" }, { "@type": "Recipe" }] },
      { "@type": "Article", mainEntityOfPage: { "@type": "Recipe", name: "Pie" } },
    ],
  };
  const nodes = flattenJsonLdNodes(input);
  assert.deepEqual(nodes.map((n) => n["@type"]), ["WebPage", "Article", "Recipe", "Article", "Recipe"]);
});

test("flattenJsonLdNodes leaves a string mainEntityOfPage alone", () => {
  const input = { "@type": "Recipe", mainEntityOfPage: "https://example.com/pie" };
  assert.equal(flattenJsonLdNodes(input).length, 1);
});

test("findRecipeNode finds the recipe under a WebPage's mainEntity", () => {
  const nodes = flattenJsonLdNodes({ "@type": "WebPage", mainEntity: { "@type": "Recipe", name: "Soup" } });
  assert.equal(findRecipeNode(nodes).name, "Soup");
});

test("findRecipeNode returns null when nothing is a recipe", () => {
  assert.equal(findRecipeNode([{ "@type": "WebPage" }, { "@type": "Organization" }]), null);
});

test("normalizeInstructions flattens a HowToSection whose @type is an array", () => {
  const input = [
    {
      "@type": ["HowToSection", "ItemList"],
      name: "Filling",
      itemListElement: [{ "@type": "HowToStep", text: "Stir." }, { "@type": "HowToStep", text: "Simmer." }],
    },
  ];
  assert.deepEqual(normalizeInstructions(input), ["Stir.", "Simmer."]);
});

test("normalizeInstructions takes a HowToStep's HowToDirection children over its own text", () => {
  const input = [
    {
      "@type": "HowToStep",
      text: "Make the dough",
      itemListElement: [
        { "@type": "HowToDirection", text: "Mix flour and water." },
        { "@type": "HowToDirection", text: "Knead for 10 minutes." },
      ],
    },
  ];
  assert.deepEqual(normalizeInstructions(input), ["Mix flour and water.", "Knead for 10 minutes."]);
});

test("normalizeInstructions falls back to the entry's text when its children have none", () => {
  const input = [{ "@type": "HowToStep", text: "Bake.", itemListElement: [{ "@type": "HowToTip" }] }];
  assert.deepEqual(normalizeInstructions(input), ["Bake."]);
});

test("normalizeInstructions flattens a text that is an array of strings", () => {
  const input = [{ "@type": "HowToStep", text: ["Mix.", "Rest.", 7] }, { text: "Bake." }];
  assert.deepEqual(normalizeInstructions(input), ["Mix.", "Rest.", "Bake."]);
});

test("normalizeInstructions flattens nested arrays and sections without a @type", () => {
  const input = [["Mix."], { itemListElement: ["Rest.", { text: "Bake." }] }];
  assert.deepEqual(normalizeInstructions(input), ["Mix.", "Rest.", "Bake."]);
});

test("normalizeImage falls back to an ImageObject's contentUrl", () => {
  assert.equal(
    normalizeImage({ "@type": "ImageObject", contentUrl: "https://example.com/a.jpg" }),
    "https://example.com/a.jpg"
  );
});

test("normalizeImage takes the first of an ImageObject's url list", () => {
  assert.equal(
    normalizeImage({ "@type": "ImageObject", url: ["https://example.com/a.jpg", "https://example.com/b.jpg"] }),
    "https://example.com/a.jpg"
  );
});

test("normalizeImage prefers url over contentUrl and skips an empty one", () => {
  assert.equal(
    normalizeImage({ url: "", contentUrl: "https://example.com/c.jpg" }),
    "https://example.com/c.jpg"
  );
  assert.equal(
    normalizeImage({ url: "https://example.com/a.jpg", contentUrl: "https://example.com/c.jpg" }),
    "https://example.com/a.jpg"
  );
});

test("normalizeImage returns null for an ImageObject with no address", () => {
  assert.equal(normalizeImage({ "@type": "ImageObject", width: 800 }), null);
});

test("decodeHtml decodes named, decimal and hex references once", () => {
  assert.equal(decodeHtml("Mac &amp; Cheese &#39;n&#x27; 350&deg;F &frac12; cup"), "Mac & Cheese 'n' 350°F ½ cup");
  assert.equal(decodeHtml("&amp;lt;b&amp;gt;"), "&lt;b&gt;");
});

test("decodeHtml leaves an unknown reference and a bare ampersand as they are", () => {
  assert.equal(decodeHtml("salt &bogus; pepper & more"), "salt &bogus; pepper & more");
  assert.equal(decodeHtml(42), 42);
});

test("cleanText strips tags, turns breaks and paragraphs into newlines and tidies whitespace", () => {
  assert.equal(cleanText("<p>Mix   the <b>flour</b>.</p><br>Bake&nbsp;it.<br/>"), "Mix the flour.\nBake it.");
  assert.equal(cleanText("  <span class=\"x\">Chicken &amp; rice</span> "), "Chicken & rice");
  assert.equal(cleanText("heat to < 350 > then serve"), "heat to < 350 > then serve");
});

test("cleanText keeps an escaped tag as text, since it was text on the page", () => {
  assert.equal(cleanText("use &lt;b&gt; sparingly"), "use <b> sparingly");
  assert.equal(cleanText(null), "");
});

function fakeDocument(jsonLd, href = "https://example.com/recipes/pie") {
  const blocks = Array.isArray(jsonLd) ? jsonLd : [jsonLd];
  return {
    location: { href },
    querySelectorAll(selector) {
      assert.equal(selector, 'script[type="application/ld+json"]');
      return blocks.map((b) => ({ textContent: typeof b === "string" ? b : JSON.stringify(b) }));
    },
  };
}

test("extractRecipeFromPage reads a whole recipe from a JSON-LD document", () => {
  const doc = fakeDocument({
    "@context": "https://schema.org",
    "@type": "Recipe",
    name: "Apple Pie",
    author: { "@type": "Person", name: "Jane Doe" },
    image: ["https://example.com/pie.jpg"],
    recipeYield: ["8 servings", "8"],
    recipeIngredient: ["6 apples", "1 crust"],
    recipeInstructions: [
      { "@type": "HowToStep", text: "Slice the apples." },
      { "@type": "HowToStep", text: "Bake 45 minutes." },
    ],
  });
  assert.deepEqual(extractRecipeFromPage(doc), {
    name: "Apple Pie",
    ingredients: ["6 apples", "1 crust"],
    steps: ["Slice the apples.", "Bake 45 minutes."],
    yield_text: "8 servings",
    author: "Jane Doe",
    source_url: "https://example.com/recipes/pie",
    image_url: "https://example.com/pie.jpg",
  });
});

test("extractRecipeFromPage finds the recipe under WebPage > mainEntity, with sections and an ImageObject", () => {
  const doc = fakeDocument({
    "@context": "https://schema.org",
    "@type": "WebPage",
    mainEntity: {
      "@type": ["Recipe", "NewsArticle"],
      name: "Lasagna",
      image: { "@type": "ImageObject", contentUrl: "https://example.com/lasagna.jpg" },
      recipeIngredient: ["1 lb pasta"],
      recipeInstructions: [
        {
          "@type": ["HowToSection"],
          name: "Sauce",
          itemListElement: [{ "@type": "HowToStep", text: ["Brown the beef.", "Add tomatoes."] }],
        },
        { "@type": "HowToStep", text: "Layer and bake." },
      ],
    },
  });
  const recipe = extractRecipeFromPage(doc);
  assert.equal(recipe.name, "Lasagna");
  assert.deepEqual(recipe.steps, ["Brown the beef.", "Add tomatoes.", "Layer and bake."]);
  assert.equal(recipe.image_url, "https://example.com/lasagna.jpg");
  assert.equal(recipe.yield_text, null);
  assert.equal(recipe.author, null);
});

test("extractRecipeFromPage decodes entities and strips tags from text, never from the URLs", () => {
  const doc = fakeDocument(
    {
      "@type": "Recipe",
      name: "Mac &amp; Cheese",
      author: "<a href=\"/jane\">Jane &#39;J&#39; Doe</a>",
      recipeYield: "4&nbsp;servings",
      image: "https://example.com/a.jpg?size=1&amp=2",
      recipeIngredient: ["<b>8 oz</b> pasta", "   ", "2 cups cheese &amp; milk"],
      recipeInstructions: "<p>Boil.</p><p>Bake at 350&deg;F.</p>",
    },
    "https://example.com/mac?a=1&amp;b=2"
  );
  assert.deepEqual(extractRecipeFromPage(doc), {
    name: "Mac & Cheese",
    ingredients: ["8 oz pasta", "2 cups cheese & milk"],
    steps: ["Boil.\nBake at 350°F."],
    yield_text: "4 servings",
    author: "Jane 'J' Doe",
    source_url: "https://example.com/mac?a=1&amp;b=2",
    image_url: "https://example.com/a.jpg?size=1&amp=2",
  });
});

test("extractRecipeFromPage skips a script that isn't JSON and keeps scanning", () => {
  const doc = fakeDocument([
    "{ not json",
    { "@type": "Organization", name: "Site" },
    { "@type": "Recipe", name: "Toast", recipeIngredient: ["bread"], recipeInstructions: "Toast it." },
  ]);
  assert.equal(extractRecipeFromPage(doc).name, "Toast");
});

test("extractRecipeFromPage returns null without a recipe, a name, ingredients or steps", () => {
  assert.equal(extractRecipeFromPage(fakeDocument({ "@type": "WebPage", name: "Nothing here" })), null);
  assert.equal(
    extractRecipeFromPage(fakeDocument({ "@type": "Recipe", name: "<br>", recipeIngredient: ["x"], recipeInstructions: "y" })),
    null
  );
  assert.equal(
    extractRecipeFromPage(fakeDocument({ "@type": "Recipe", name: "No steps", recipeIngredient: ["x"] })),
    null
  );
  assert.equal(
    extractRecipeFromPage(fakeDocument({ "@type": "Recipe", name: "No food", recipeIngredient: [], recipeInstructions: "y" })),
    null
  );
});

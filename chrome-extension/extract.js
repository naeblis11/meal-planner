function flattenJsonLdNodes(value) {
  if (Array.isArray(value)) {
    return value.flatMap(flattenJsonLdNodes);
  }
  if (value && typeof value === "object") {
    if (Array.isArray(value["@graph"])) {
      return value["@graph"].flatMap(flattenJsonLdNodes);
    }
    return [value];
  }
  return [];
}

function isRecipeNode(node) {
  const type = node && node["@type"];
  if (!type) return false;
  const types = Array.isArray(type) ? type : [type];
  return types.some((t) => typeof t === "string" && t.endsWith("Recipe"));
}

function findRecipeNode(nodes) {
  return nodes.find(isRecipeNode) || null;
}

function normalizeInstructionEntry(entry) {
  if (typeof entry === "string") {
    return [entry];
  }
  if (entry && typeof entry === "object") {
    if (entry["@type"] === "HowToSection" && Array.isArray(entry.itemListElement)) {
      return entry.itemListElement.flatMap(normalizeInstructionEntry);
    }
    if (typeof entry.text === "string") {
      return [entry.text];
    }
  }
  return [];
}

function normalizeInstructions(recipeInstructions) {
  if (typeof recipeInstructions === "string") {
    return [recipeInstructions];
  }
  if (Array.isArray(recipeInstructions)) {
    return recipeInstructions.flatMap(normalizeInstructionEntry);
  }
  return [];
}

function normalizeImage(image) {
  if (!image) return null;
  if (typeof image === "string") return image;
  if (Array.isArray(image)) {
    for (const entry of image) {
      const url = normalizeImage(entry);
      if (url) return url;
    }
    return null;
  }
  if (typeof image === "object" && typeof image.url === "string") {
    return image.url;
  }
  return null;
}

function normalizeYield(recipeYield) {
  if (!recipeYield) return null;
  if (Array.isArray(recipeYield)) {
    return recipeYield.length ? String(recipeYield[0]) : null;
  }
  return String(recipeYield);
}

function normalizeAuthor(author) {
  if (!author) return null;
  if (Array.isArray(author)) {
    return author.length ? normalizeAuthor(author[0]) : null;
  }
  if (typeof author === "string") return author;
  if (typeof author === "object" && typeof author.name === "string") {
    return author.name;
  }
  return null;
}

function extractRecipeFromPage() {
  const scripts = Array.from(
    document.querySelectorAll('script[type="application/ld+json"]')
  );
  const nodes = [];
  for (const script of scripts) {
    try {
      nodes.push(...flattenJsonLdNodes(JSON.parse(script.textContent)));
    } catch (err) {
      // Not valid JSON in this particular tag -- skip it, keep scanning.
    }
  }

  const recipeNode = findRecipeNode(nodes);
  if (!recipeNode) return null;

  const name = typeof recipeNode.name === "string" ? recipeNode.name.trim() : "";
  const ingredients = Array.isArray(recipeNode.recipeIngredient)
    ? recipeNode.recipeIngredient.filter((i) => typeof i === "string" && i.trim())
    : [];
  const steps = normalizeInstructions(recipeNode.recipeInstructions).filter(
    (s) => typeof s === "string" && s.trim()
  );

  if (!name || ingredients.length === 0 || steps.length === 0) {
    return null;
  }

  return {
    name,
    ingredients,
    steps,
    yield_text: normalizeYield(recipeNode.recipeYield),
    author: normalizeAuthor(recipeNode.author),
    source_url: window.location.href,
    image_url: normalizeImage(recipeNode.image),
  };
}

if (typeof window !== "undefined") {
  window.__extractRecipeFromPage = extractRecipeFromPage;
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    flattenJsonLdNodes,
    isRecipeNode,
    findRecipeNode,
    normalizeInstructions,
    normalizeImage,
    normalizeYield,
    normalizeAuthor,
    extractRecipeFromPage,
  };
}

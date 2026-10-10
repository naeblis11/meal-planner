function flattenJsonLdNodes(value) {
  if (Array.isArray(value)) {
    return value.flatMap(flattenJsonLdNodes);
  }
  if (value && typeof value === "object") {
    const nodes = Array.isArray(value["@graph"])
      ? value["@graph"].flatMap(flattenJsonLdNodes)
      : [value];
    // A WebPage (or an Article) may carry the recipe as its mainEntity rather than beside it; the
    // node itself is kept too. mainEntityOfPage is usually a string or a bare WebPage reference.
    for (const key of ["mainEntity", "mainEntityOfPage"]) {
      const inner = value[key];
      if (inner && typeof inner === "object") {
        nodes.push(...flattenJsonLdNodes(inner));
      }
    }
    return nodes;
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
  if (Array.isArray(entry)) {
    return entry.flatMap(normalizeInstructionEntry);
  }
  if (entry && typeof entry === "object") {
    // Whatever the @type says (HowToSection, a HowToStep holding HowToDirections, ...), a list of
    // children is the steps; the entry's own text is only used when the children give none.
    if (Array.isArray(entry.itemListElement)) {
      const children = entry.itemListElement.flatMap(normalizeInstructionEntry);
      if (children.length) return children;
    }
    if (typeof entry.text === "string") {
      return [entry.text];
    }
    if (Array.isArray(entry.text)) {
      return entry.text.filter((t) => typeof t === "string");
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
  if (typeof image === "object") {
    // An ImageObject's url or contentUrl, either of which may itself be a list.
    return normalizeImage(image.url) || normalizeImage(image.contentUrl);
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

// The few entities sites put in JSON-LD text, for when there is no HTML parser (the Node tests).
// In the page, DOMParser decodes every named entity.
const NAMED_ENTITIES = {
  amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ",
  deg: "°", frac12: "½", frac14: "¼", frac34: "¾", times: "×",
  ndash: "–", mdash: "—", lsquo: "‘", rsquo: "’", ldquo: "“",
  rdquo: "”", hellip: "…", eacute: "é", egrave: "è", ntilde: "ñ",
  copy: "©", reg: "®", trade: "™",
};

// Decodes HTML character references once (so "&amp;lt;" becomes "&lt;", not "<"). Tags are not
// touched here; the text handed in has had them stripped already (cleanText).
function decodeHtml(text) {
  if (typeof text !== "string" || text.indexOf("&") === -1) return text;
  if (typeof DOMParser !== "undefined") {
    const parsed = new DOMParser().parseFromString(text, "text/html");
    return parsed.documentElement.textContent;
  }
  return text.replace(/&(#x[0-9a-f]+|#[0-9]+|[a-z][a-z0-9]*);/gi, (match, ref) => {
    if (ref[0] === "#") {
      const code = ref[1] === "x" || ref[1] === "X" ? parseInt(ref.slice(2), 16) : parseInt(ref.slice(1), 10);
      return code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : match;
    }
    return Object.prototype.hasOwnProperty.call(NAMED_ENTITIES, ref) ? NAMED_ENTITIES[ref] : match;
  });
}

// Text for a name, an ingredient, a step, a yield or an author: tags out (a line break or a
// paragraph becomes a newline), entities decoded once, whitespace tidied. Never for a URL.
function cleanText(value) {
  if (typeof value !== "string") return "";
  const text = value
    .replace(/<\/?(?:br|p|div|li)\b[^>]*>/gi, "\n")
    .replace(/<\/?[a-zA-Z][^>]*>/g, "");
  return decodeHtml(text)
    .replace(/[ \t\r\f\v ]+/g, " ")
    .replace(/\s*\n\s*/g, "\n")
    .trim();
}

function extractRecipeFromPage(doc = document) {
  const scripts = Array.from(
    doc.querySelectorAll('script[type="application/ld+json"]')
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

  const name = cleanText(recipeNode.name);
  const ingredients = Array.isArray(recipeNode.recipeIngredient)
    ? recipeNode.recipeIngredient.map(cleanText).filter((i) => i)
    : [];
  const steps = normalizeInstructions(recipeNode.recipeInstructions)
    .map(cleanText)
    .filter((s) => s);

  if (!name || ingredients.length === 0 || steps.length === 0) {
    return null;
  }

  return {
    name,
    ingredients,
    steps,
    yield_text: cleanText(normalizeYield(recipeNode.recipeYield)) || null,
    author: cleanText(normalizeAuthor(recipeNode.author)) || null,
    source_url: doc.location ? doc.location.href : null,
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
    decodeHtml,
    cleanText,
    extractRecipeFromPage,
  };
}

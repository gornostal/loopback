// Bun macro: runs at transpile/bundle time and inlines the file contents as a string literal,
// so `bun build --compile` produces a self-contained binary that carries the skill files.
import { readFileSync } from "node:fs";
import { join } from "node:path";

export function embedSkillFile(relativePath: string): string {
  return readFileSync(join(import.meta.dir, "..", "skill", relativePath), "utf8");
}

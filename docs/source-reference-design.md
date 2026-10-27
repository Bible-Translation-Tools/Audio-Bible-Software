# A different reference beside a project (design)

Step 12 of the source-editions plan. **Designed now, built later** (S12-Q1): nothing here is
implemented except the tests that pin the diff API it relies on
(`StructuralDiffReferenceTest`).

## What it is for

A translator records against a project's source. They may want to see or hear something else
beside it:

- another edition of the same source (ULB v12 beside ULB 24-07);
- another source in the same language (UDB beside ULB);
- a source in another language (a Russian or French Bible beside the English ULB).

A reference can be text, audio or both. It never changes the project: the project's verses stay
those of its own source edition, chapter by chapter (steps 4 and 8). A reference is only lined up
with them.

## Storage

One new table, beside the book's own source (which stays `collection_entity.source_fk`):

```sql
CREATE TABLE project_reference (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    project_collection_fk  INTEGER NOT NULL REFERENCES collection_entity(id) ON DELETE CASCADE,
    edition_fk             INTEGER NOT NULL REFERENCES dublin_core_entity(id),
    role                   TEXT NOT NULL,     -- 'text', 'audio' or 'both'
    sort                   INTEGER NOT NULL,  -- order in the reference picker
    UNIQUE (project_collection_fk, edition_fk)
);
```

- `project_collection_fk` is the project's **book** collection: references are per book, like the
  source (S1-Q1).
- Deleting the project deletes its references (cascade). Foreign keys are on for every connection
  since step 8.
- An edition used as a reference is **in use**: `EditionLifecycle.isInUse` counts it, so an upgrade
  or a newer install doesn't retire it. Deleting the last project that uses it lets it be retired,
  as for held-back chapters (step 11's fix to `DeleteProject`).
- Backups: `source_editions.json` gains an optional `references` list (format version 2), each an
  identity, label and fingerprint like `BackupEdition`. References are not embedded, which keeps
  backups small. On restore a reference is reattached when an installed edition matches its
  fingerprint, and otherwise dropped and logged.

## Lining a reference up with the project

For each chapter the reference view needs a map from the project's verse units to the
reference's. It is built from step 7's diff, with the caller choosing what to pass:

1. **The project side:** the chapter's verses as its structure edition has them (the same text
   `UpgradeBookEdition.plan` compares from).
2. **The reference side:** the same book and chapter of the reference edition.
3. **Text or no text:**
   - Same language: pass the text. The diff pairs verses by text first, so it finds merges, folds
     and moves (English ULB Acts 19:41 folded into 19:40).
   - Another language: pass the verses **without text**. A textless verse is only matched by number
     (pinned). Passing the text is harmless too, since other-language text never looks alike enough
     to be taken for a move or fold (pinned), but it adds nothing.
4. **Versification:** when the two editions' detected versifications differ (`detected_versification`,
   stored since step 1) and both are known, renumber both sides into `org` with the Copenhagen
   `mappedVerses` before comparing. Then map the result back. Without this, a textless comparison
   can't see a move such as English Malachi 4:1-6 = `org` 3:19-24 (pinned both ways).
5. **The map:** each diff group maps its `from` units to its `to` units. A merge maps several
   project verses to one reference verse; a split maps one to several; an added or removed verse
   maps to nothing.

This generalises `ReferenceAlignment` (step 8), which does steps 1, 3 and 5 for one case: a
held-back chapter against its book's newer edition. When this is built, its private `align` becomes
the shared core, keyed by (project book, chapter, structure edition, reference edition,
versification pair), and cached the same way.

### What has to be built

- `VersificationMap`: parse `mappedVerses` entries (`"GEN 31:55": "GEN 32:1"`,
  `"EXO 8:1-4": "EXO 7:26-29"`, ranges expanded verse by verse) into
  `toOrg(book, chapter, verse)` and `fromOrg(...)`. `ParatextVersification` already parses the map.
- Renumbering an `EditionText` into `org`: moving verses between chapters where the map says so,
  and keying chapters by the `org` chapter's slug.
- The table and its DAO, an `IProjectReferenceRepository` port (interfaces only, per the port
  boundary test), and `EditionLifecycle.isInUse` counting references.
- The UI: a picker of installed editions, and a panel showing the reference's text for the current
  verse and playing its audio.

### Limits

- Across languages, a fold or a move that no mapping entry describes can't be seen. For example,
  a project with Acts 19:41 against another-language reference that folded 41 into 40: with no
  text to compare, 41 maps to nothing. For such a verse the view shows the whole chapter's
  reference instead of one verse.
- Copenhagen `partialVerses` (a verse split between two in another versification) are shown as the
  whole verse on each side.
- A reference without the project's book offers nothing for that book.

## Audio (S12-Q2)

A reference's audio plays one verse only when that audio has verse markers; otherwise it plays the
whole chapter. The verse to play is the mapped start of the project verse, as
`SourceAudioPlayerController` already does for held-back chapters (step 8). A project verse that
maps to nothing plays the whole chapter.

## Questions for design and the product owner, when this is picked up

- Where the reference sits in the recorder (beside the source text, or in place of it on a toggle),
  and on small phones.
- How many references a book can have at once, and whether the choice is per book or remembered per
  language pair.
- Whether a reference in another language shows its text at all, or audio only.

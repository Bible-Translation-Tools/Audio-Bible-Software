---
trigger: always_on
---

#Repository Access Rule

Context: Using repositories to access projects Restriction: Direct access to CollectionRepository and ContentRepository is STRICTLY PROHIBITED within the Domain and Application layers. Requirement: All data persistence and retrieval must go through the WorkbookRepository. Reasoning: WorkbookRepository manages observable field synchronization and project structure integrity. Bypassing it will cause state desync between the UI and the database. It is what forms a domain model of a translation project with a target and source, the books, chapters, chunks/verses, and associated audio which are audio files representing the chapter or chunk/verse.
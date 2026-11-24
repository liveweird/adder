# Personal room organization

- **Spec**: [tests/room-organization.spec.ts](../tests/room-organization.spec.ts)
- **Actors**: A throwaway administrator and two people who share rooms.
- **Owns**: Two throwaway member accounts, three rooms, and their private organization.

## Scenario: private room groups and room order follow the account

1. Both people join the same three rooms. One opens Organize rooms from the directory at phone width.
2. They create two named groups, collapse one and use touch to drag a room onto its header. They move the other rooms through the action menu.
   - *Expected*: The collapsed destination opens and focus follows moved rooms.
3. They reorder the rooms through a keyboard menu and drag a group above the other with a mouse, then save.
   - *Expected*: DOM order reflects both moves, Ungrouped stays last and the editor passes an axe scan.
4. The same person signs in from a fresh browser context.
   - *Expected*: The sidebar shows the saved groups and rooms in their new order.
5. The other person signs in from a separate context and opens the organizer.
   - *Expected*: Their rooms appear under Ungrouped. The other person's groups are absent.

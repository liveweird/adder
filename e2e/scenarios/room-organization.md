# Personal room organization

- **Spec**: [tests/room-organization.spec.ts](../tests/room-organization.spec.ts)
- **Actors**: A throwaway administrator and two people who share rooms.
- **Owns**: Two throwaway member accounts, three rooms, and their private organization.

## Scenario: private room groups and room order follow the account

1. Both people join the same three rooms. One opens Organize rooms from the directory at phone width.
2. They create two named groups and move the three rooms into them.
   - *Expected*: Focus follows the room's group selector after a move.
3. They change the order of the groups and of two rooms with the keyboard, then save.
   - *Expected*: The editor reflects both moves and passes an axe scan.
4. The same person signs in from a fresh browser context.
   - *Expected*: The sidebar shows the saved groups and rooms in their new order.
5. The other person signs in from a separate context and opens the organizer.
   - *Expected*: Their rooms appear under Ungrouped. The other person's groups are absent.

# Personal Home

- **Spec**: [tests/home.spec.ts](../tests/home.spec.ts)
- **Actors**: A throwaway administrator, room owner, colleague, and applicant.
- **Owns**: Its throwaway users, two rooms, thread activity, watch, and join request.

## Scenario: Home shows personal quotes, owned threads, watched threads, and room requests

1. An owner starts a thread in a room they own. A colleague replies by quoting one of the owner's replies. Another person requests to join that room.
2. The owner joins the colleague's room and watches a thread there.
3. The owner opens Home at phone width.
   - *Expected*: The quote, owned thread, watched thread, and owned room appear in their respective sections. The joined room is absent from the owned-room list. An axe scan finds no WCAG A/AA violations.
4. The owner opens the quote and returns Home.
   - *Expected*: The link opens the reply that quotes the owner in its thread. The reply still appears on Home after it is read.
5. The owner opens the join requests from the owned room.
   - *Expected*: Members takes them directly to the pending request and its approval control.

/**
 * Which audience a dual-mode page is serving: the signed-in user looking at their own data, or an admin
 * looking at a selected user's. It comes from the route's `data`, so a page never has to infer it from the
 * presence of a URL parameter.
 */
export type PageAudience = 'USER' | 'ADMIN';

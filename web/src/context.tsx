import { createContext, useContext } from "react";
import type {
  Account,
  Category,
  Preferences,
  Receipt,
  Route,
  Transaction,
  User,
  Organization,
} from "./types";

export type ModalState =
  | {
      type: "transaction";
      transaction?: Transaction;
      kind?: string;
      refund?: Transaction;
    }
  | { type: "upload" }
  | { type: "receipt"; receipt: Receipt }
  | null;
export type AppContextType = {
  user: User;
  organization: Organization;
  isAdmin: boolean;
  switchOrganization: (id: string) => Promise<void>;
  month: string;
  accounts: Account[];
  categories: Category[];
  prefs?: Preferences;
  navigate: (route: Route) => void;
  open: (modal: ModalState) => void;
  toast: (message: string) => void;
};
export const AppContext = createContext<AppContextType | null>(null);
export function useApp() {
  const context = useContext(AppContext);
  if (!context) throw new Error("Missing app context");
  return context;
}

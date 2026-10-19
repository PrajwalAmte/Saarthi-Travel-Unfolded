import Link from "next/link";
import { Brand } from "./brand";

export function SiteFooter() {
  return (
    <footer className="mt-20 border-t border-line bg-sunken/60">
      <div className="container-page grid gap-8 py-10 text-sm text-ink-2 sm:grid-cols-3">
        <div className="space-y-2">
          <Brand />
          <p className="pt-1 text-muted">
            A free, open-source travel guide to India, built from open data and the people who know each place.
          </p>
        </div>
        <div className="space-y-2">
          <p className="font-medium text-ink">Data and licences</p>
          <ul className="space-y-1">
            <li>
              Guide text from{" "}
              <a className="underline" href="https://en.wikivoyage.org/" rel="noopener noreferrer">
                Wikivoyage
              </a>{" "}
              contributors,{" "}
              <a className="underline" href="https://creativecommons.org/licenses/by-sa/4.0/" rel="noopener noreferrer">
                CC BY-SA 4.0
              </a>
            </li>
            <li>
              Place data from{" "}
              <a className="underline" href="https://www.wikidata.org/" rel="noopener noreferrer">
                Wikidata
              </a>{" "}
              (CC0)
            </li>
            <li>
              Weather data by{" "}
              <a className="underline" href="https://open-meteo.com/" rel="noopener noreferrer">
                Open-Meteo.com
              </a>{" "}
              (CC BY 4.0)
            </li>
            <li>
              <Link className="underline" href="/sources">
                All sources and attribution
              </Link>
            </li>
          </ul>
        </div>
        <div className="space-y-2">
          <p className="font-medium text-ink">Project</p>
          <ul className="space-y-1">
            <li>
              <Link className="underline" href="/about">
                How information is checked
              </Link>
            </li>
            <li>
              <Link className="underline" href="/privacy">
                Privacy and data retention
              </Link>
            </li>
            <li>
              <Link className="underline" href="/signin">
                Become a contributor
              </Link>
            </li>
            <li>Software licensed under Apache-2.0</li>
          </ul>
        </div>
      </div>
    </footer>
  );
}

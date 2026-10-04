"use client";

import React from "react";
import { motion, AnimatePresence } from "framer-motion";
import { TrendingUp, Target, DollarSign, ListOrdered, Calendar } from "lucide-react";

type ResultsWidgetProps = {
  isRunning: boolean;
  results: any;
};

export default function ResultsWidget({ isRunning, results }: ResultsWidgetProps) {
  
  if (!isRunning && !results) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center text-center opacity-50 relative z-0">
        <div className="w-64 h-64 absolute bg-[#00f0ff] rounded-full blur-[150px] opacity-10" />
        <DatabaseIcon className="w-16 h-16 text-slate-600 mb-4" />
        <h3 className="text-xl font-display font-bold text-slate-300">Awaiting Parameters</h3>
        <p className="text-slate-500 max-w-sm mt-2 text-sm">
          Build your custom trend by selecting filters from the sidebar, then hit Run Backtest to execute sub-millisecond queries.
        </p>
      </div>
    );
  }

  return (
    <div className="flex-1 relative z-0 flex flex-col gap-6">
      
      <AnimatePresence mode="wait">
        {isRunning ? (
          <motion.div
            key="skeleton"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="w-full space-y-6"
          >
            <div className="grid grid-cols-3 gap-6">
              {[1, 2, 3].map((i) => (
                <div key={i} className="glass-panel p-6 rounded-2xl h-32 relative overflow-hidden">
                  <div className="absolute inset-0 bg-gradient-to-r from-transparent via-white/5 to-transparent w-[200%] animate-[shimmer_1.5s_infinite] -translate-x-full" />
                  <div className="w-8 h-8 rounded bg-white/10 mb-4" />
                  <div className="w-24 h-8 rounded bg-white/10 mb-2" />
                  <div className="w-16 h-4 rounded bg-white/5" />
                </div>
              ))}
            </div>
            <div className="glass-panel rounded-2xl h-64 p-6 relative overflow-hidden">
              <div className="absolute inset-0 bg-gradient-to-r from-transparent via-white/5 to-transparent w-[200%] animate-[shimmer_1.5s_infinite] -translate-x-full" />
              <div className="w-1/4 h-6 rounded bg-white/10 mb-8" />
              <div className="space-y-4">
                {[1, 2, 3, 4].map((j) => (
                  <div key={j} className="w-full h-8 rounded bg-white/5" />
                ))}
              </div>
            </div>
          </motion.div>
        ) : results ? (
          <motion.div
            key="results"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, staggerChildren: 0.1 }}
            className="w-full space-y-6"
          >
            {/* KPI Cards */}
            <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
              
              <MetricCard 
                title="Return on Investment" 
                value={`${results.roi > 0 ? '+' : ''}${results.roi}%`}
                icon={<TrendingUp className="text-[#00f0ff]" />}
                colorClass="text-[#00f0ff]"
                glowClass="shadow-[0_0_30px_rgba(0,240,255,0.15)] border-[#00f0ff]/30"
                subtext="Based on standard -110 juice"
              />
              
              <MetricCard 
                title="Historical Win Rate" 
                value={`${results.winRate}%`}
                icon={<Target className="text-[#ff0055]" />}
                colorClass="text-[#ff0055]"
                glowClass="shadow-[0_0_30px_rgba(255,0,85,0.15)] border-[#ff0055]/30"
                subtext={`${results.wins}W - ${results.losses}L - 0P`}
              />
              
              <MetricCard 
                title="Net Profit (Units)" 
                value={`${results.netProfit > 0 ? '+' : ''}${results.netProfit}u`}
                icon={<DollarSign className="text-[#00ffaa]" />}
                colorClass="text-[#00ffaa]"
                glowClass="shadow-[0_0_30px_rgba(0,255,170,0.15)] border-[#00ffaa]/30"
                subtext={`Total games: ${results.totalMatches}`}
              />

            </div>

            {/* Simulated Game Log Table */}
            <div className="glass-panel rounded-2xl overflow-hidden flex flex-col">
              <div className="p-6 border-b border-white/5 flex items-center justify-between">
                <h3 className="text-lg font-display font-bold text-white flex items-center gap-2">
                  <ListOrdered className="w-5 h-5 text-slate-400" />
                  Historical Game Logs
                </h3>
                <span className="text-xs bg-white/10 px-3 py-1 rounded-full text-slate-300">Showing latest 5 matches</span>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-sm text-left">
                  <thead className="text-xs text-slate-400 uppercase bg-white/5">
                    <tr>
                      <th className="px-6 py-4">Date</th>
                      <th className="px-6 py-4">Matchup</th>
                      <th className="px-6 py-4">Bet Placed</th>
                      <th className="px-6 py-4">Result</th>
                      <th className="px-6 py-4 text-right">Units</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-white/5">
                    {[
                      { d: '2023-12-10', m: 'PHI @ DAL', b: 'DAL -3.5', r: 'WIN', u: '+1.00', c: 'text-green-400' },
                      { d: '2023-11-26', m: 'BUF @ PHI', b: 'PHI -3.0', r: 'PUSH', u: '0.00', c: 'text-slate-400' },
                      { d: '2023-11-20', m: 'PHI @ KC', b: 'PHI +2.5', r: 'WIN', u: '+1.00', c: 'text-green-400' },
                      { d: '2023-10-22', m: 'MIA @ PHI', b: 'PHI -2.5', r: 'WIN', u: '+1.00', c: 'text-green-400' },
                      { d: '2023-10-15', m: 'PHI @ NYJ', b: 'PHI -6.5', r: 'LOSS', u: '-1.10', c: 'text-red-400' },
                    ].map((row, idx) => (
                      <tr key={idx} className="hover:bg-white/5 transition-colors">
                        <td className="px-6 py-4 whitespace-nowrap text-slate-300 flex items-center gap-2">
                          <Calendar className="w-4 h-4 text-slate-500" />
                          {row.d}
                        </td>
                        <td className="px-6 py-4 font-medium text-white">{row.m}</td>
                        <td className="px-6 py-4 font-mono text-slate-300">{row.b}</td>
                        <td className={`px-6 py-4 font-bold ${row.c}`}>{row.r}</td>
                        <td className={`px-6 py-4 font-mono text-right ${row.c}`}>{row.u}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

          </motion.div>
        ) : null}
      </AnimatePresence>
      
      {/* Tailwind animation keyframes injected via class arbitrary variants or simple style block */}
      <style dangerouslySetInnerHTML={{__html: `
        @keyframes shimmer {
          100% { transform: translateX(100%); }
        }
      `}} />
    </div>
  );
}

function MetricCard({ title, value, icon, colorClass, glowClass, subtext }: any) {
  return (
    <div className={`glass-panel p-6 rounded-2xl relative overflow-hidden border ${glowClass} transition-all hover:scale-[1.02] duration-300`}>
      <div className="flex items-start justify-between">
        <div className="p-3 rounded-xl bg-white/5 backdrop-blur-md">
          {icon}
        </div>
      </div>
      <div className="mt-4">
        <h4 className="text-sm font-medium text-slate-400">{title}</h4>
        <div className={`text-4xl font-display font-bold mt-1 ${colorClass}`}>
          {value}
        </div>
        <p className="text-xs text-slate-500 mt-2 font-medium">{subtext}</p>
      </div>
      {/* Subtle background glow */}
      <div className={`absolute -right-8 -bottom-8 w-32 h-32 rounded-full blur-[50px] opacity-20 bg-current ${colorClass}`} />
    </div>
  );
}

function DatabaseIcon(props: any) {
  return (
    <svg
      {...props}
      xmlns="http://www.w3.org/2000/svg"
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <ellipse cx="12" cy="5" rx="9" ry="3" />
      <path d="M3 5V19A9 3 0 0 0 21 19V5" />
      <path d="M3 12A9 3 0 0 0 21 12" />
    </svg>
  );
}

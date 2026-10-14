      * TSKREC - task master record, VSAM KSDS TSKMSTR, key TSK-ID
       01  TSK-RECORD.
           05  TSK-ID              PIC X(10).
           05  TSK-TITLE           PIC X(40).
           05  TSK-DESC            PIC X(200).
           05  TSK-STATUS          PIC X(01).
               88  TSK-OPEN                VALUE 'O'.
               88  TSK-IN-PROGRESS         VALUE 'I'.
               88  TSK-DONE                VALUE 'D'.
               88  TSK-DELETED             VALUE 'X'.
           05  TSK-USER            PIC X(08).
           05  TSK-DUE-DATE        PIC 9(08).
           05  TSK-ORIGIN          PIC X(01).
               88  TSK-FROM-HOST           VALUE 'H'.
               88  TSK-FROM-TASKBOARD      VALUE 'T'.
           05  TSK-LAST-UPD        PIC X(26).
           05  FILLER              PIC X(06).
